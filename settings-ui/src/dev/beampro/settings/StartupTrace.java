package dev.beampro.settings;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Passive startup evidence retained independently of the noisy application log ring. */
final class StartupTrace {
    static final int MAX_RECENT_BYTES=96_000, MAX_FAILURE_BYTES=64_000, MAX_REPORT_BYTES=165_000;
    private static final int MAX_LINE_BYTES=8192, FAILURE_CONTEXT_BYTES=48_000;
    private static final Pattern LOG=Pattern.compile("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+\\s+\\d+\\s+\\d+\\s+[VDIWEF]\\s+([^:]{1,64}):\\s?(.*)$");
    private static final Pattern TIMEOUT=Pattern.compile("\\b([0-9]{1,5}) get rsp timeout\\b");
    private static final Pattern SOURCE=Pattern.compile("\\((?:NRServiceControl|XREALManager|OtaPresenterImpl|GlassesInitSetting|GlassesManager|PingGSeriesHelper|UsbAttachDetachHelper)\\.java:");
    private static final Pattern SDK_BODY=Pattern.compile("(?i)NRSDK Version:|HMD version:|get rsp timeout|GetDisplaySupportedRois|GetDisplayDpResolutionMap|startup.?state|nativeGetGlassesStartupState|nativeGlassesInit|new connection|remove connection|bind to net device|Lifecycle (?:Initialize|Start|Stop)|NRSDK (?:Create|Init|RUN|STOP)|NRGlasses (?:START|RUN|STOP)|Leopard Mcu (?:Initializ|Start|Stop)|Device Connect|clock correlation");
    private static final Pattern JAVA_BODY=Pattern.compile("(?i)startSdk|startupState|nativeGetGlassesStartupState|sdkVersion|isEthernetSeries|init=|currentUsbConfigList|set (?:mtp|ncm|ecm)|mtp to|USB config|Waiting for|ping result|PingSuccess|onPlugOut|stop ping|currentDisplayMode|finalDbDisplay|modeResult");
    private static final Pattern FAIL=Pattern.compile("(?i)get rsp timeout|startupState=,?\\s*-1|nativeGetGlassesStartupState.*errorCode,\\s*[1-9]|startSdk: (?:failed|result=false)|could not|timed out|connection timed out|cannot (?:start|prepare|finish|open)|disconnected or could not|USB access failed");
    private final File recentFile, failureFile, stateFile;
    private final ArrayDeque<String> recent=new ArrayDeque<>();
    private final ByteArrayOutputStream partial=new ByteArrayOutputStream();
    private final TreeMap<Integer,Long> timeouts=new TreeMap<>();
    private int recentBytes;
    private boolean oversized, signaled, pinOpen;
    private long droppedLines;
    private String pinned="", attempt="", sdk="", firmware="", init="", state="", apiError="";

    StartupTrace(File directory) throws IOException {
        recentFile=new File(directory,"startup-recent.txt");
        failureFile=new File(directory,"startup-first-failure.txt");
        stateFile=new File(directory,"startup-state.bin");
        String saved=read(recentFile,MAX_RECENT_BYTES);
        for(String line:saved.split("\\n")) if(!line.isEmpty()) addRecent(line+"\n");
        pinned=read(failureFile,MAX_FAILURE_BYTES);
        if(stateFile.isFile()&&stateFile.length()<=8192) try(DataInputStream input=new DataInputStream(new FileInputStream(stateFile))) {
            if(input.readInt()!=1)throw new IOException("Unknown startup trace state");
            attempt=input.readUTF();signaled=input.readBoolean();droppedLines=input.readLong();
            sdk=input.readUTF();firmware=input.readUTF();init=input.readUTF();state=input.readUTF();apiError=input.readUTF();
            int count=input.readInt();if(count<0||count>32)throw new IOException("Invalid startup timeout summary");
            for(int i=0;i<count;i++){int id=input.readInt();long total=input.readLong();if(id>=0&&total>0)timeouts.put(id,total);}
        } catch(IOException corrupt) {
            // Keep the separately persisted first-failure window even if its summary was interrupted.
            attempt="";signaled=false;timeouts.clear();sdk=firmware=init=state=apiError="";droppedLines=0;
        }
        // A restarted process must not append a different attempt to the first failure window.
        pinOpen=false;
    }

    synchronized void begin(boolean reset) throws IOException {
        partial.reset();oversized=false;
        if(reset) {
            recent.clear();recentBytes=0;pinned="";pinOpen=false;signaled=false;attempt="";
            sdk=firmware=init=state=apiError="";timeouts.clear();droppedLines=0;
            remove(failureFile);remove(recentFile);remove(stateFile);
        }
        event("",reset?"collection_begin":"collection_resume","");
    }

    synchronized boolean log(byte[] bytes,int count) throws IOException {
        if(count<0||count>bytes.length)throw new IllegalArgumentException("Invalid startup log byte count");
        boolean trigger=false,changed=false;
        for(int i=0;i<count;i++) {
            int value=bytes[i]&255;
            if(value=='\n') {
                if(!oversized) {
                    String line=new String(partial.toByteArray(),StandardCharsets.UTF_8);
                    if(line.endsWith("\r"))line=line.substring(0,line.length()-1);
                    Matcher match=LOG.matcher(line);
                    if(match.matches()&&selected(match.group(1).trim(),match.group(2))) {
                        trigger|=record(line,match.group(2));changed=true;
                    }
                } else {droppedLines++;changed=true;}
                partial.reset();oversized=false;
            } else if(!oversized) {
                if(partial.size()<MAX_LINE_BYTES)partial.write(value);
                else {partial.reset();oversized=true;}
            }
        }
        if(changed)save();
        return trigger;
    }

    synchronized void event(String id,String event,String detail) throws IOException {
        String safeId=clean(id,128),safeEvent=clean(event,96),safeDetail=clean(detail,1500);
        if((safeEvent.equals("begin")||safeEvent.equals("sdk_start"))&&!safeId.equals(attempt)) {
            attempt=safeId;signaled=false;pinOpen=false;
        }
        SimpleDateFormat stamp=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);
        stamp.setTimeZone(TimeZone.getTimeZone("UTC"));
        String line=stamp.format(new Date())+" StartupTrace attempt="+safeId+" event="+safeEvent;
        if(!safeDetail.isEmpty())line+=" detail="+safeDetail;
        record(line,safeEvent+" "+safeDetail);save();
    }

    synchronized boolean hasData(){return !recent.isEmpty()||!pinned.isEmpty();}

    synchronized byte[] report() {
        StringBuilder output=new StringBuilder("Glasses startup evidence\nPassive app-UID logs and connection events; no native getters are invoked.\n");
        output.append("\nObserved summary since this collection began\n");
        output.append("Timeout request IDs/counts:");
        if(timeouts.isEmpty())output.append(" none observed");
        for(Map.Entry<Integer,Long> item:timeouts.entrySet())output.append(' ').append(item.getKey()).append('=').append(item.getValue());
        output.append('\n');
        summary(output,"Last native SDK version",sdk);summary(output,"Last glasses firmware",firmware);
        summary(output,"Last native initialization",init);summary(output,"Last startup state",state);summary(output,"Last startup API error",apiError);
        output.append("Oversized log lines discarded: ").append(droppedLines).append('\n');
        output.append("\n--- First failure window (retained across retries and process restarts) ---\n");
        output.append(pinned.isEmpty()?"No failure observed.\n":pinned);
        output.append("\n--- Recent startup timeline ---\n");
        for(String line:recent)output.append(line);
        return encode(output.toString(),MAX_REPORT_BYTES);
    }

    private static boolean selected(String tag,String body) {
        if(tag.equals("UnboundConnect")||tag.equals("UnboundUSB")||tag.equals("UnboundEthernet")||tag.equals("UnboundStartup"))return true;
        if(tag.equals("XREAL"))return SDK_BODY.matcher(body).find();
        if(tag.equals("myGlasses")||tag.equals("LogControl"))return SOURCE.matcher(body).find()&&JAVA_BODY.matcher(body).find();
        return false;
    }

    private boolean record(String line,String body) {
        if(!pinned.isEmpty()&&pinOpen&&body.contains("NRSDK Version:"))pinOpen=false;
        String framed=line+"\n";addRecent(framed);
        Matcher timeout=TIMEOUT.matcher(body);
        if(timeout.find()) {
            int request=Integer.parseInt(timeout.group(1));
            if(timeouts.containsKey(request)||timeouts.size()<32)timeouts.put(request,Math.min(Long.MAX_VALUE-1,timeouts.getOrDefault(request,0L))+1);
        }
        int at=body.indexOf("NRSDK Version:");if(at>=0)sdk=clean(body.substring(at+14),512);
        at=body.indexOf("HMD version:");if(at>=0)firmware=clean(body.substring(at+12),512);
        if(body.contains("init=,")||body.contains("nativeGlassesInit"))init=clean(body,512);
        if(body.contains("startupState="))state=clean(body,512);
        if(body.contains("nativeGetGlassesStartupState")&&body.contains("errorCode"))apiError=clean(body,512);
        boolean failure=FAIL.matcher(body).find()||body.startsWith("failure ")||body.equals("failure");
        if(failure&&pinned.isEmpty()) {
            pinned=tail(FAILURE_CONTEXT_BYTES);pinOpen=true;
        } else if(pinOpen&&!pinned.isEmpty()&&pinned.getBytes(StandardCharsets.UTF_8).length+framed.getBytes(StandardCharsets.UTF_8).length<=MAX_FAILURE_BYTES) {
            pinned+=framed;
        }
        if(failure&&!signaled){signaled=true;return true;}
        return false;
    }

    private void addRecent(String line) {
        int size=line.getBytes(StandardCharsets.UTF_8).length;
        recent.addLast(line);recentBytes+=size;
        while(recentBytes>MAX_RECENT_BYTES&&!recent.isEmpty())recentBytes-=recent.removeFirst().getBytes(StandardCharsets.UTF_8).length;
    }

    private String tail(int limit) {
        ArrayDeque<String> lines=new ArrayDeque<>();int size=0;
        for(java.util.Iterator<String> iterator=recent.descendingIterator();iterator.hasNext();) {
            String line=iterator.next();int bytes=line.getBytes(StandardCharsets.UTF_8).length;
            if(size+bytes>limit)break;
            lines.addFirst(line);size+=bytes;
        }
        StringBuilder output=new StringBuilder();for(String line:lines)output.append(line);return output.toString();
    }

    private void save() throws IOException {
        StringBuilder output=new StringBuilder();for(String line:recent)output.append(line);
        write(recentFile,output.toString().getBytes(StandardCharsets.UTF_8));
        if(!pinned.isEmpty())write(failureFile,pinned.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream state=new DataOutputStream(bytes)) {
            state.writeInt(1);state.writeUTF(attempt);state.writeBoolean(signaled);state.writeLong(droppedLines);
            state.writeUTF(sdk);state.writeUTF(firmware);state.writeUTF(init);state.writeUTF(this.state);state.writeUTF(apiError);
            state.writeInt(timeouts.size());for(Map.Entry<Integer,Long> item:timeouts.entrySet()){state.writeInt(item.getKey());state.writeLong(item.getValue());}
        }
        write(stateFile,bytes.toByteArray());
    }

    private static void summary(StringBuilder output,String label,String value){output.append(label).append(": ").append(value.isEmpty()?"not observed":value).append('\n');}
    private static String clean(String value,int limit) {
        if(value==null)return "";
        if(Pattern.compile("(?i)pairing.?code|password|SharedPreferences|StatusBarNotification").matcher(value).find())return "[detail omitted]";
        return new String(encode(value.replace('\n',' ').replace('\r',' '),limit),StandardCharsets.UTF_8).replace('\n',' ').trim();
    }

    static byte[] encode(String text,int limit) {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        if(bytes.length<=limit)return bytes;
        byte[] notice="\n[truncated]\n".getBytes(StandardCharsets.UTF_8);
        int end=Math.max(0,limit-notice.length);
        while(end>0&&(bytes[end]&0xc0)==0x80)end--;
        byte[] result=new byte[end+notice.length];System.arraycopy(bytes,0,result,0,end);System.arraycopy(notice,0,result,end,notice.length);return result;
    }

    private static String read(File file,int limit) throws IOException {
        if(!file.isFile())return "";
        try(FileInputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int remaining=limit,count;
            while(remaining>0&&(count=input.read(buffer,0,Math.min(buffer.length,remaining)))!=-1){output.write(buffer,0,count);remaining-=count;}
            return new String(output.toByteArray(),StandardCharsets.UTF_8);
        }
    }

    private static void write(File file,byte[] bytes) throws IOException {
        File temporary=new File(file.getPath()+".tmp");
        try {
            try(FileOutputStream output=new FileOutputStream(temporary)){output.write(bytes);}
            if(!temporary.renameTo(file))throw new IOException("Could not retain startup evidence");
        } finally {if(temporary.exists())temporary.delete();}
    }
    private static void remove(File file) throws IOException{if(file.exists()&&!file.delete())throw new IOException("Could not reset startup evidence");}
}
