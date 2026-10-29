package dev.beampro.settings;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class DisplayDiagnosticsTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            if (args[0].equals("slow")) { System.out.println("partial display dump"); Thread.sleep(10000); }
            else if (args[0].equals("noisy")) { byte[] block=new byte[8192];Arrays.fill(block,(byte)'x');for(int i=0;i<160;i++)System.out.write(block);System.out.print("LATEST_DISPLAY_LINK_EVENT"); }
            else if (args[0].equals("unicode")) { System.out.print(new String(new char[20_000]).replace('\0','\u20ac')); }
            else { System.err.println("Permission Denial: cannot dump SurfaceFlinger"); System.exit(9); }
            return;
        }
        for (int kind : new int[]{DisplayDiagnostics.DISPLAY, DisplayDiagnostics.SURFACE_FLINGER}) {
            String report=DisplayDiagnostics.capture(kind,(timeout,command)->{
                check(timeout==10000,"process has a bounded deadline");
                boolean windows=kind==DisplayDiagnostics.DISPLAY&&Arrays.equals(command,new String[]{"/system/bin/dumpsys","-t","5","window","displays"});
                check(windows||Arrays.equals(command,new String[]{"/system/bin/dumpsys","-t","8",DisplayDiagnostics.service(kind)}),"only fixed read-only commands");
                return windows ? "exit=0; timedOut=false\nmCurrentFocus and InsetsState\n" : "exit=0; timedOut=false\nphysical modes and policy\n";
            });
            check(report.contains("Identity: ADB shell helper")&&report.contains("Captured:")&&report.contains("physical modes"),"capture records identity, timestamp and output");
            if(kind==DisplayDiagnostics.DISPLAY)check(report.contains("Connected Presentation focus and inset control")&&report.contains("mCurrentFocus and InsetsState"),"live window focus and inset state survive beside display modes");
        }
        try { DisplayDiagnostics.capture(99,(t,c)->{throw new AssertionError("invalid selector ran");});throw new AssertionError("accepted invalid service"); }
        catch(IllegalArgumentException expected){checks++;}
        String report=DisplayDiagnostics.capture(0,(t,c)->{throw new java.io.IOException("missing dumpsys");});
        check(report.contains("Unavailable:")&&report.contains("missing dumpsys"),"missing commands remain diagnostic evidence");
        String java=System.getProperty("java.home")+"/bin/java", cp=System.getProperty("java.class.path");
        report=NetworkDiagnostics.runLimited(1_000_000,5000,java,"-cp",cp,DisplayDiagnosticsTest.class.getName(),"noisy");
        check(report.contains("exit=0")&&report.contains("output limited to 1000000 bytes")&&report.length()<1_000_200,"large stdout drains without blocking and reports truncation");
        report=NetworkDiagnostics.runTailLimited(32_000,5000,java,"-cp",cp,DisplayDiagnosticsTest.class.getName(),"noisy");
        check(report.contains("LATEST_DISPLAY_LINK_EVENT")&&report.contains("last 32000 bytes")&&report.length()<32_200,"large log sources preserve the newest event within a fixed tail budget");
        report=NetworkDiagnostics.runTailLimited(32_000,5000,java,"-cp",cp,DisplayDiagnosticsTest.class.getName(),"unicode");
        check(!report.contains("\ufffd")&&report.getBytes(StandardCharsets.UTF_8).length<32_200,"tail truncation starts on a valid UTF-8 boundary");
        long start=System.nanoTime();
        report=NetworkDiagnostics.runLimited(1_000_000,200,java,"-cp",cp,DisplayDiagnosticsTest.class.getName(),"slow");
        check(report.contains("partial display dump")&&report.contains("timedOut=true")&&(System.nanoTime()-start)/1_000_000<2500,"timeouts preserve partial output and kill the process");
        report=NetworkDiagnostics.runLimited(1_000_000,3000,java,"-cp",cp,DisplayDiagnosticsTest.class.getName(),"denied");
        check(report.contains("exit=9")&&report.contains("Permission Denial"),"permission errors and exit status retained");
        String unicode=new String(new char[DisplayDiagnostics.MAX_BYTES]).replace('\0','\u20ac');
        byte[] encoded=DisplayDiagnostics.encode(unicode);
        check(encoded.length<=DisplayDiagnostics.MAX_BYTES,"file budget is bytes, not UTF-16 characters");
        check(!new String(encoded,StandardCharsets.UTF_8).contains("\ufffd"),"truncation preserves UTF-8 boundaries");
        check(new String(encoded,StandardCharsets.UTF_8).contains("truncated"),"file-level truncation is explicit");
        java.util.List<String[]> commands=new java.util.ArrayList<>();
        report=DisplayDiagnostics.capture(DisplayDiagnostics.DISPLAY_LINK,(timeout,command)->{
            commands.add(command);
            check(timeout>0&&timeout<=4000,"each link probe has a bounded deadline");
            if(command[0].endsWith("dmesg"))return "exit=1; timedOut=false\ndmesg: Operation not permitted\n";
            if(Arrays.asList(command).contains("kernel"))return "exit=0; timedOut=false\n[drm] DP link training failed\nunrelated private kernel message\n";
            return "exit=0; timedOut=false\nDisplayPort Alt Mode and EDID fixture\n";
        });
        check(commands.size()==7,"all seven fixed link sources collected");
        check(Arrays.equals(commands.get(0),new String[]{"/system/bin/dumpsys","-t","3","usb"}),"USB port and Alt Mode service dump");
        check(Arrays.equals(commands.get(1),new String[]{"/system/bin/dumpsys","-t","2","window","displays"}),"display windows only");
        check(Arrays.asList(commands.get(2)).contains("*:S")&&!Arrays.asList(commands.get(2)).contains("all"),"system log read is restricted to fixed display and USB tags");
        check(commands.get(5)[0].equals("/system/bin/sh")&&commands.get(5)[2].contains("/sys/class/drm/card*-DP-*")
                &&commands.get(5)[2].contains("/system/bin/timeout -s KILL 1")&&commands.get(5)[2].contains("-N512"),"fixed sysfs paths, per-read deadlines and bounded EDID");
        check(Arrays.equals(commands.get(6),new String[]{"/system/bin/head","-c","4096","/data/local/tmp/unbound-launcher/display-policy.properties"}),"only the helper display-policy journal is read");
        check(report.contains("DP link training failed")&&report.contains("Operation not permitted")&&!report.contains("unrelated private"),"retain link evidence and denials while filtering unrelated kernel messages");
        int[] probes={0};
        report=DisplayDiagnostics.capture(DisplayDiagnostics.DISPLAY_LINK,(t,c)->{
            probes[0]++;if(probes[0]==1)throw new java.io.IOException("USB dump unavailable");return "exit=0; timedOut=false\n";
        });
        check(probes[0]==7&&report.contains("USB dump unavailable")&&report.contains("Helper temporary display-policy"),"one unavailable source cannot suppress later link probes");
        Thread.currentThread().interrupt();
        try {
            report=DisplayDiagnostics.capture(DisplayDiagnostics.DISPLAY_LINK,(t,c)->{throw new AssertionError("interrupted capture ran");});
            check(report.contains("probe skipped")&&Thread.currentThread().isInterrupted(),"interrupted capture skips remaining subprocesses without clearing cancellation");
        } finally {Thread.interrupted();}
        encoded=DisplayDiagnostics.encode(DisplayDiagnostics.DISPLAY_LINK,unicode);
        check(encoded.length<=DisplayDiagnostics.MAX_LINK_BYTES&&!new String(encoded,StandardCharsets.UTF_8).contains("\ufffd")
                &&new String(encoded,StandardCharsets.UTF_8).contains("truncated"),"link snapshots use a smaller explicit UTF-8 byte budget");
        commands.clear();int[] counterReads={0};
        report=DisplayDiagnostics.capture(DisplayDiagnostics.GLASSES_CONTROL,(timeout,command)->{
            commands.add(command);check(timeout>0&&timeout<=800,"control probes each have a short bounded deadline");
            if(command[0].endsWith("/sh")&&command[2].contains("@@ $table"))return "exit=0; timedOut=false\n@@ /proc/net/tcp\n"
                    +"  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"
                    +"   0: 0A02FEA9:8CC8 0102FEA9:CF07 01 00000027:00000000 01:00000032 00000002 10345        0 4242 1 0000000000000000 20 4 30 10 -1\n"
                    +"   1: 1002000A:9250 0477D9AC:01BB 01 00000000:00000000 00:00000000 00000000 10103        0 57341 1 0000000000000000 25 4 28 10 -1\n"
                    +"@@ /proc/net/tcp6\nhead: /proc/net/tcp6: Permission denied\n@@ /proc/net/udp\n@@ /proc/net/udp6\n";
            if(command[0].endsWith("/sh")) {
                counterReads[0]++;
                return "exit=0; timedOut=false\neth0.operstate=up\neth0.rx_bytes="+(counterReads[0]==1?100:135)
                        +"\neth0.tx_packets="+(counterReads[0]==1?3:5)+"\neth1.rx_errors="+(counterReads[0]==1?9:0)+"\n";
            }
            if(command[0].endsWith("/ss"))return "exit=0; timedOut=false\nCannot open netlink socket: Permission denied\n"
                    +"tcp ESTAB 0 0 169.254.2.10:40000 169.254.2.1:9000 users:((private-process,pid=123))\n"
                    +"udp ESTAB 0 0 169.254.1.10:40001 169.254.1.1:9001\n"
                    +"tcp ESTAB 0 0 192.0.2.1:50000 203.0.113.123:443\n"
                    +"tcp ESTAB 0 0 169.254.2.1:50000 203.0.113.124:443\n"
                    +"tcp ESTAB 0 0 192.0.2.1:50000 169.254.2.10:9000\n";
            return "exit=0; timedOut=false\n169.254.2.1 dev eth0 src 169.254.2.10 uid 2000\n";
        });
        check(commands.size()==7&&counterReads[0]==2,"counter snapshots surround two socket, one kernel-table and two route queries");
        check(Arrays.equals(commands.get(1),new String[]{"/system/bin/ss","-H","-n","-4","-t","-u","-a","dst","169.254.2.1"})
                &&commands.get(2)[8].equals("169.254.1.1"),"socket tool uses only the two fixed glasses destinations");
        check(commands.get(3)[0].equals("/system/bin/sh")&&commands.get(3)[2].contains("/proc/net/tcp /proc/net/tcp6 /proc/net/udp /proc/net/udp6")
                &&commands.get(3)[2].contains("/system/bin/timeout -s KILL 1 /system/bin/head -c 1048576"),"kernel socket tables are fixed paths with per-read and byte bounds");
        check(report.contains("tcp ESTABLISHED 169.254.2.10:36040>169.254.2.1:52999 uid=10345 ino=4242 sendq=39 recvq=0 timer=retransmit(500ms) retrans=2")
                &&report.contains("head: /proc/net/tcp6: Permission denied")&&!report.contains("0477D9AC")&&!report.contains("57341"),"snapshot decodes glasses rows with timers and owners, keeps denials and drops other peers");
        check(Arrays.equals(commands.get(4),new String[]{"/system/bin/ip","route","get","169.254.2.1"})
                &&commands.get(5)[3].equals("169.254.1.1"),"route probes are fixed read-only lookups");
        check(commands.get(0)[2].equals(commands.get(6)[2])&&commands.get(0)[2].contains("for iface in eth0 eth1")
                &&commands.get(0)[2].contains("statistics/rx_errors")&&commands.get(0)[2].contains("statistics/tx_dropped")
                &&commands.get(0)[2].contains("/system/bin/timeout -s KILL 1")
                &&commands.get(0)[2].contains("/system/bin/grep -E '^ *eth[01]:' /proc/net/dev"),"fixed interface/link/counter paths retain per-read bounds, with a /proc/net/dev fallback");
        check(report.contains("eth0.rx_bytes=35")&&report.contains("eth0.tx_packets=2")
                &&report.contains("eth1.rx_errors=counter decreased or reset"),"numeric deltas and counter resets are explicit");
        check(report.contains("Cannot open netlink socket: Permission denied")&&report.contains("169.254.2.1:9000")
                &&report.contains("169.254.1.1:9001")&&!report.contains("203.0.113")&&!report.contains("private-process")
                &&!report.contains("169.254.2.10:9000"),"socket export keeps fixed peers and restrictions while removing unrelated peers/process names");
        check(report.contains("monotonic_ns=")&&report.contains("wall_ms=")&&report.contains("duration_ms=")
                &&report.contains("do not prove request delivery or firmware receipt"),"timing and measurement limits accompany each snapshot");
        counterReads[0]=0;
        report=DisplayDiagnostics.capture(DisplayDiagnostics.GLASSES_CONTROL,(timeout,command)->{
            if(!command[0].endsWith("/sh")||command[2].contains("@@ $table"))return "exit=0; timedOut=false\n";
            counterReads[0]++;
            return "exit=0; timedOut=false\n\nInterface: eth0\nInterface unavailable in sysfs\n\n/proc/net/dev rows for eth0/eth1:\n"
                    +"  eth0: 5000 "+(counterReads[0]==1?40:47)+" 0 0 0 0 0 0 9000 "+(counterReads[0]==1?30:39)+" 0 0 0 0 0 0\n";
        });
        check(report.contains("eth0.rx_packets=7")&&report.contains("eth0.tx_packets=9")&&report.contains("eth0.rx_bytes=0")
                &&report.contains("sysfs, else /proc/net/dev"),"phones that deny sysfs statistics still produce interface deltas from /proc/net/dev");
        report=DisplayDiagnostics.capture(DisplayDiagnostics.GLASSES_CONTROL,(timeout,command)->{throw new java.io.IOException("ss unavailable");});
        check(report.contains("ss unavailable")&&report.contains("Counter deltas unavailable"),"missing commands stay readable without suppressing snapshot completion");
        encoded=DisplayDiagnostics.encode(DisplayDiagnostics.GLASSES_CONTROL,unicode);
        check(encoded.length<=DisplayDiagnostics.MAX_CONTROL_BYTES&&!new String(encoded,StandardCharsets.UTF_8).contains("\ufffd")
                &&new String(encoded,StandardCharsets.UTF_8).contains("truncated"),"control snapshots have a strict 32000-byte UTF-8 limit");
        probes[0]=0;start=System.nanoTime();
        report=DisplayDiagnostics.capture(DisplayDiagnostics.GLASSES_CONTROL,(timeout,command)->{probes[0]++;Thread.sleep(4100);return "exit=-1; timedOut=true\n";});
        check(probes[0]==1&&report.contains("Capture deadline reached; probe skipped")&&(System.nanoTime()-start)/1_000_000<5500,
                "whole-capture deadline skips later probes after a stalled source");
        Thread.currentThread().interrupt();
        try {
            report=DisplayDiagnostics.capture(DisplayDiagnostics.GLASSES_CONTROL,(t,c)->{throw new AssertionError("interrupted control capture ran");});
            check(report.contains("Interrupted; probe skipped")&&Thread.currentThread().isInterrupted(),"control capture retains cancellation and skips all commands");
        } finally {Thread.interrupted();}
        check(DisplayDiagnostics.service(DisplayDiagnostics.GLASSES_CONTROL_TRACE).equals("Glasses control trace"),"trace selector has its own service name");
        try{DisplayDiagnostics.capture(DisplayDiagnostics.GLASSES_CONTROL_TRACE,(t,c)->{throw new AssertionError("trace used a subprocess");});throw new AssertionError("trace accepted a runner");}
        catch(IllegalArgumentException expected){checks++;}
        encoded=DisplayDiagnostics.encode(DisplayDiagnostics.GLASSES_CONTROL_TRACE,unicode);
        check(encoded.length<=ControlTrace.MAX_BYTES&&!new String(encoded,StandardCharsets.UTF_8).contains("\ufffd")
                &&new String(encoded,StandardCharsets.UTF_8).contains("truncated"),"control traces have a strict 24000-byte UTF-8 limit");
        System.out.println("PASS display diagnostic assertions: "+checks);
    }
}
