package dev.beampro.settings;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public final class StartupTraceTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static String nativeLine(String body){return "10-28 12:11:27.500  3557 25772 I XREAL   : [2026-10-28 12:11:27.500] [3899] [INFO] [NRService] "+body+"\n";}
    static String connection(String body){return "10-28 12:11:27.500  3557  3557 I UnboundConnect: "+body+"\n";}
    static boolean log(StartupTrace trace,String text)throws Exception{byte[] bytes=text.getBytes(StandardCharsets.UTF_8);return trace.log(bytes,bytes.length);}
    static String report(StartupTrace trace){return new String(trace.report(),StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception {
        File root=new File(args[0]);root.mkdirs();StartupTrace trace=new StartupTrace(root);
        check(!trace.hasData(),"No unsolicited startup export before collection");
        trace.begin(true);trace.event("attempt-1","begin","connected XREAL 1S");
        trace.event("attempt-1","sdk_start","wallMs=1; pid=2; displays=[94 \"XREAL One Pro\" 1920x1080@90.0 mode=188 state=2 supported=1920x1080@90.0/120.0/60.0]; phase=Starting AR Space");
        log(trace,nativeLine("HMD Device type(local):11,11"));log(trace,nativeLine("Supported Devices: 1537 -> 1537"));
        check(!log(trace,nativeLine("NRSDK Version: 3.1.2.20261009101910")),"SDK version is evidence, not failure");
        log(trace,nativeLine("HMD version: system:3563, software:2.0.0.20260901182643"));
        log(trace,"10-28 12:11:28.655  3557  3899 D myGlasses: (NRServiceControl.java:249)#lambda$startSdk$0 | [init=, true]\n");
        String first=nativeLine("333 get rsp timeout");byte[] split=first.getBytes(StandardCharsets.UTF_8);int triggers=0;
        for(byte value:split)if(trace.log(new byte[]{value},1))triggers++;
        check(triggers==1,"A timeout split across individual byte chunks triggers one immediate capture");
        check(!log(trace,nativeLine("334 get rsp timeout")),"Later timeout in the same attempt is rate limited");
        check(!log(trace,nativeLine("265 get rsp timeout")),"Startup-state timeout shares the capture boundary");
        log(trace,"10-28 12:11:29.167  3557  3899 D myGlasses: (NRServiceControl.java:132)#checkError | [preMethodName, nativeGetGlassesStartupState, errorCode, 1]\n");
        log(trace,"10-28 12:11:29.167  3557  3899 D myGlasses: (NRServiceControl.java:263)#lambda$startSdk$0 | [startupState=, -1, mDpInitStateGSeries =false]\n");
        String initial=report(trace);
        check(initial.contains("333=1 334=1")&&initial.contains("265=1"),"Deterministic observed request counts");
        check(initial.contains("3.1.2.20261009101910")&&initial.contains("system:3563"),"Native SDK and glasses firmware versions retained");
        check(initial.contains("[init=, true]")&&initial.contains("errorCode, 1")&&initial.contains("startupState=, -1"),"Observed initialization, API error and startup state preserved");
        check(initial.contains("Glasses display at last SDK start: 94 \"XREAL One Pro\" 1920x1080@90.0 mode=188 state=2 supported=1920x1080@90.0/120.0/60.0\n"),"Display mode at SDK start is summarized without the following connection detail");
        check(initial.contains("HMD Device type(local):11,11")&&initial.contains("Supported Devices: 1537"),"Native model negotiation preceding the first control request is retained");
        trace.event("attempt-1","begin","duplicate boundary");
        check(!log(trace,nativeLine("265 get rsp timeout")),"Duplicate begin event cannot reset capture rate limit");
        trace.event("attempt-2","begin","retry");
        check(log(trace,nativeLine("15 get rsp timeout")),"A new attempt permits the first failure capture");
        for(int request:new int[]{240,229,16,13})check(!log(trace,nativeLine(request+" get rsp timeout")),"Retry command timeout remains in the same capture boundary");
        log(trace,nativeLine("HMD version: system:, software:"));
        check(report(trace).contains("Last glasses firmware: system:3563, software:2.0.0.20260901182643"),"A retry's empty version strings do not replace the real firmware");
        byte[] pinned=Files.readAllBytes(new File(root,"startup-first-failure.txt").toPath());
        String frozen=new String(pinned,StandardCharsets.UTF_8);
        check(frozen.contains("333 get rsp timeout")&&frozen.contains("334 get rsp timeout")&&frozen.contains("265 get rsp timeout"),"First failure window includes the control-query sequence after the initial timeout");
        check(!frozen.contains("attempt-2")&&!frozen.contains("15 get rsp timeout"),"Retries do not enter the pinned first failure window");
        String notification="10-28 12:11:30.500  3557  3557 I NotifyService: StatusBarNotification body=UnboundConnect 333 get rsp timeout pairing code 123456\n";
        StringBuilder noise=new StringBuilder();for(int i=0;i<10000;i++)noise.append(notification);
        check(!log(trace,noise.toString()),"Unrelated notification bodies never trigger startup capture");
        check(!report(trace).contains("StatusBarNotification")&&!report(trace).contains("123456"),"Prioritized export excludes notification and pairing payloads");
        StringBuilder later=new StringBuilder();for(int i=0;i<1800;i++)later.append(connection("Later polling stage "+i+" "+new String(new char[90]).replace('\0','x')));
        log(trace,later.toString());
        check(new File(root,"startup-recent.txt").length()<=96_000&&new File(root,"startup-first-failure.txt").length()<=64_000,"Both persisted trace windows have independent byte limits");
        check(Arrays.equals(pinned,Files.readAllBytes(new File(root,"startup-first-failure.txt").toPath())),"Recent timeline rotation cannot replace first-failure evidence");
        trace=new StartupTrace(root);trace.begin(false);
        check(report(trace).contains("333 get rsp timeout")&&report(trace).contains("13=1"),"Process restart preserves first failure and observed summary");
        log(trace,connection("after restart"));
        check(Arrays.equals(pinned,Files.readAllBytes(new File(root,"startup-first-failure.txt").toPath())),"Restart freezes the previous attempt's first failure window");
        trace.event("attempt-3","sdk_start","new process request");
        check(log(trace,nativeLine("265 get rsp timeout")),"A distinct attempt after restart allows immediate diagnostics");
        trace.event("attempt-3","failure","pairing code 654321");
        check(!report(trace).contains("654321"),"Direct details omit pairing-code text");
        trace.begin(true);
        check(!report(trace).contains("333 get rsp timeout")&&report(trace).contains("none observed")
                &&report(trace).contains("Glasses display at last SDK start: not observed"),"Explicit collection reset clears old failure windows and summary");
        File legacy=new File(args[0]+"-v1");legacy.mkdirs();
        try(DataOutputStream state=new DataOutputStream(new FileOutputStream(new File(legacy,"startup-state.bin")))) {
            state.writeInt(1);state.writeUTF("old-attempt");state.writeBoolean(true);state.writeLong(0);
            state.writeUTF("3.1.2.20261009101910");state.writeUTF("system:3563, software:2.0.0.20260901182643");state.writeUTF("");state.writeUTF("");state.writeUTF("");
            state.writeInt(1);state.writeInt(333);state.writeLong(2);
        }
        String upgraded=report(new StartupTrace(legacy));
        check(upgraded.contains("333=2")&&upgraded.contains("system:3563")&&upgraded.contains("Glasses display at last SDK start: not observed"),"Summary state written by the released build still loads after updating");
        String unicode=connection("Waiting for glasses — café 💙");byte[] unicodeBytes=unicode.getBytes(StandardCharsets.UTF_8);
        for(int i=0;i<unicodeBytes.length;i+=3)trace.log(Arrays.copyOfRange(unicodeBytes,i,Math.min(i+3,unicodeBytes.length)),Math.min(3,unicodeBytes.length-i));
        check(report(trace).contains("café 💙")&&!report(trace).contains("�"),"UTF-8 characters survive arbitrary streaming byte boundaries");
        log(trace,connection(new String(new char[10000]).replace('\0','z')+" get rsp timeout"));
        log(trace,connection("valid line after an oversized line"));
        check(report(trace).contains("Oversized log lines discarded: 1")&&report(trace).contains("valid line after"),"Oversized framing recovers at the next newline without inventing a failure");
        byte[] bounded=StartupTrace.encode(new String(new char[32000]).replace("\0","💙"),32000);
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bounded));
        check(bounded.length<=32000&&new String(bounded,StandardCharsets.UTF_8).endsWith("[truncated]\n"),"Snapshot truncation preserves valid UTF-8 and its byte cap");
        check(trace.report().length<=StartupTrace.MAX_REPORT_BYTES,"Trace report includes headers and summary inside its export cap");
        try{trace.log(new byte[1],2);throw new AssertionError("Oversized input count accepted");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("PASS startup trace assertions: "+checks);
    }
}
