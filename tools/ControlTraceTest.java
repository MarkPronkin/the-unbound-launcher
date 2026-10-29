package dev.beampro.settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class ControlTraceTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static final String TCP_HEADER="  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n";
    // Unrelated rows copied from an Android 13 emulator's /proc/net/tcp; glasses rows use the same layout.
    static final String UNRELATED="   0: 1002000A:9250 0477D9AC:01BB 08 00000000:00000082 00:00000000 00000000 10103        0 57341 1 0000000000000000 25 4 28 10 -1\n";
    static String tcp(String state,String queues,String timer,String retrans,String inode) {
        return "   1: 0A02FEA9:8CC8 0102FEA9:CF07 "+state+" "+queues+" "+timer+" "+retrans+" 10345        0 "+inode+" 1 0000000000000000 20 4 30 10 -1\n";
    }

    static final class Script implements ControlTrace.Source, ControlTrace.Clock {
        long nanos;int sample=-1;String[] tcp;boolean denyTcp6;int devReads;
        Script(String... tcp){this.tcp=tcp;}
        public String read(String path) throws IOException {
            if(path.equals("/proc/net/dev")) {
                devReads++;
                return "Inter-|   Receive\n face |bytes packets errs drop\n wlan0: 999 99 0 0 0 0 0 0 999 99 0 0 0 0 0 0\n"
                        +"  eth0:"+(1000+sample*10)+" "+(10+sample)+" 0 0 0 0 0 0 2000 "+(20+sample)+" 0 1 0 0 0 0\n";
            }
            if(path.equals("/proc/net/tcp")){sample++;return TCP_HEADER+UNRELATED+tcp[Math.min(sample,tcp.length-1)];}
            if(path.equals("/proc/net/tcp6")&&denyTcp6)throw new IOException("Permission denied");
            return "";
        }
        public long nanos(){return nanos;}
        public long wallMs(){return 1_793_233_933_000L+nanos/1_000_000L;}
        public void sleep(long millis){nanos+=millis*1_000_000L;}
    }

    public static void main(String[] args) throws Exception {
        check("169.254.2.1:52999".equals(ControlTrace.address("0102FEA9:CF07")),"Little-endian IPv4 words and host-order ports decode");
        check("169.254.2.10:36040".equals(ControlTrace.address("0A02FEA9:8CC8")),"Local endpoint decodes");
        check("169.254.1.1:52999".equals(ControlTrace.address("0000000000000000FFFF00000101FEA9:CF07")),"IPv4-mapped tcp6 endpoints decode");
        check(ControlTrace.address("00000000000000000000000001000000:0016")==null,"Native IPv6 endpoints are not glasses peers");

        String table=TCP_HEADER+UNRELATED+tcp("01","00000027:00000000","01:00000032","00000003","4242")
                +"   2: 0A02FEA9:BA1C 0102FEA9:CF07 05 00000000:00000000 00:00000000 00000000 10345        0 0 1 0000000000000000 20 4 30 10 -1\n"
                +"   3: 0A02FEA9:BA20 0102FEA9:CF07 06 00000000:00000000 03:00001770 00000000     0        0 0 3 0000000000000000\n"
                +"   4: not a socket row\n";
        List<ControlTrace.Socket> sockets=ControlTrace.parse(table,"tcp");
        check(sockets.size()==3,"Glasses rows retained, unrelated peer and malformed rows skipped");
        ControlTrace.Socket stalled=sockets.get(0);
        check(stalled.sendQueue==39&&stalled.receiveQueue==0&&stalled.timer==1&&stalled.timerMs==500&&stalled.retransmits==3
                &&stalled.uid==10345&&stalled.inode==4242&&stalled.rtoMs==200&&stalled.cwnd==10&&stalled.ssthresh==-1,"TCP queues, timer ticks, retransmits, owner and congestion fields decode");
        String described=stalled.describe();
        check(described.startsWith("tcp ESTABLISHED 169.254.2.10:36040>169.254.2.1:52999 uid=10345 ino=4242 sendq=39 recvq=0 timer=retransmit(500ms) retrans=3"),"Readable socket line: "+described);
        check(sockets.get(1).describe().contains("FIN_WAIT2")&&sockets.get(2).describe().contains("TIME_WAIT")&&sockets.get(2).rtoMs==-1,"Short TIME_WAIT rows decode without congestion fields");
        String zeroWindow=tcp("01","00000400:00000000","04:000000C8","00000000","4243");
        check(ControlTrace.parse(TCP_HEADER+zeroWindow,"tcp").get(0).describe().contains("timer=zero-window-probe(2000ms)"),"Zero-window probe timer is named");
        String unread=tcp("01","00000000:00000080","00:00000000","00000000","4244");
        check(ControlTrace.parse(TCP_HEADER+unread,"tcp").get(0).describe().contains("sendq=0 recvq=128 timer=none"),"Unread reply bytes are visible");
        String udp="   sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops\n"
                +"  62: 0A02FEA9:0044 0102FEA9:0043 01 00000000:00000000 00:00000000 00000000  1000        0 27895 2 0000000000000000 7\n"
                +" 103: 00000000:14E9 00000000:0000 07 00000000:00000000 00:00000000 00000000  1020        0 45091 2 0000000000000000 0\n";
        sockets=ControlTrace.parse(udp,"udp");
        check(sockets.size()==1&&sockets.get(0).describe().equals("udp ESTABLISHED 169.254.2.10:68>169.254.2.1:67 uid=1000 ino=27895 sendq=0 recvq=0 drops=7"),"Connected UDP peers and drops decode; unconnected sockets excluded");

        Map<String,Long> counters=ControlTrace.deviceCounters(" wlan0: 5 6 0 0 0 0 0 0 7 8 0 0 0 0 0 0\n  eth0:12345678 90 1 2 0 0 0 0 555 66 3 4 0 0 0 0\n  eth1: 10 2 0 0 0 0 0 0 20 4 0 0 0 0 0 0\nInterface: eth0\n");
        check(counters.get("eth0.rx_bytes")==12345678L&&counters.get("eth0.rx_packets")==90L&&counters.get("eth0.rx_dropped")==2L
                &&counters.get("eth0.tx_packets")==66L&&counters.get("eth0.tx_dropped")==4L&&counters.get("eth1.tx_bytes")==20L,"/proc/net/dev rows decode, including counters touching the colon");
        check(!counters.containsKey("wlan0.rx_bytes")&&counters.size()==16,"Only the fixed glasses interfaces are retained");

        String probe="@@ /proc/net/tcp\n"+table+"@@ /proc/net/tcp6\nhead: /proc/net/tcp6: No such file or directory\n@@ /proc/net/udp\n"+udp+"@@ /proc/net/udp6\n";
        String decoded=ControlTrace.tables("exit=0; timedOut=false\n"+probe);
        check(decoded.contains("exit=0")&&decoded.contains("No such file or directory")&&decoded.contains("sendq=39")&&decoded.contains("drops=7")
                &&!decoded.contains("0477D9AC")&&!decoded.contains("172.217")&&!decoded.contains("not a socket"),"Snapshot table probe keeps errors and decoded glasses rows only");
        check(ControlTrace.tables("@@ /proc/net/tcp\n"+TCP_HEADER+UNRELATED).contains("[No fixed-peer rows"),"Empty result is explicit");

        // Five samples: absent, connected, request outstanding, retransmitting, closed.
        String idle=tcp("01","00000000:00000000","00:00000000","00000000","4242");
        Script script=new Script("",idle,tcp("01","00000027:00000000","01:00000014","00000000","4242"),
                tcp("01","00000027:00000000","01:00000005","00000000","4242"),tcp("01","00000027:00000000","01:00000032","00000001","4242"),"");
        script.denyTcp6=true;
        String trace=ControlTrace.capture(script,script,250,50);
        check(trace.contains("Service: Glasses control trace")&&trace.contains("Identity: ADB shell helper"),"Trace identifies its producer");
        String[] lines=trace.split("\n");int news=0,changes=0,gone=0;
        for(String line:lines){if(line.contains(" NEW tcp"))news++;else if(line.contains(" GONE tcp"))gone++;else if(line.matches("\\d\\d:\\d\\d:\\d\\d\\.\\d{3} \\+\\d+ms tcp .*"))changes++;}
        check(news==1&&gone==1&&changes==2,"Only appearance, real state changes and closure are written ("+news+"/"+changes+"/"+gone+")\n"+trace);
        check(trace.contains("+50ms NEW tcp ESTABLISHED")&&trace.contains("+100ms tcp ESTABLISHED")&&trace.contains("sendq=39")
                &&trace.contains("retrans=1")&&trace.contains("+250ms GONE"),"Relative timestamps follow the sampling clock\n"+trace);
        check(!trace.contains("timer=retransmit(50ms)"),"A moving timer alone does not write a line");
        check(trace.contains("| eth0 rx=")&&trace.contains("Interface counters at start: eth0 rx=")&&trace.contains("Interface counter deltas: eth0.rx_packets="),"Interface packet counters accompany changes and bound the trace");
        check(trace.contains("Read failures for /proc/net/tcp6: 6; first: java.io.IOException: Permission denied"),"Denied tables are counted once per path without stopping other tables");
        check(trace.contains("samples=6;")&&trace.contains("changes=4;")&&trace.contains("lines_omitted_at_byte_limit=0"),"End summary counts samples and changes");
        check(trace.contains("--- Sockets open at end ---\nnone"),"Closed sockets are not reported open");
        check(script.devReads==6,"Interface counters are read at start, for change samples and at end only ("+script.devReads+")");

        StringBuilder busy=new StringBuilder(TCP_HEADER);String[] samples=new String[400];
        for(int i=0;i<samples.length;i++) {
            StringBuilder rows=new StringBuilder();
            for(int s=0;s<8;s++)rows.append(String.format("   %d: 0A02FEA9:%04X 0102FEA9:CF07 01 %08X:00000000 00:00000000 00000000 10345        0 %d 1 0000000000000000 20 4 30 10 -1\n",s,0x9000+s,i+s,5000+s));
            samples[i]=rows.toString();
        }
        script=new Script(samples);
        trace=ControlTrace.capture(script,script,20_000,50);
        byte[] bytes=trace.getBytes(StandardCharsets.UTF_8);
        check(bytes.length<=ControlTrace.MAX_BYTES&&trace.contains("lines_omitted_at_byte_limit=")&&!trace.contains("lines_omitted_at_byte_limit=0;"),"A busy socket set stays within the byte budget and reports omitted lines ("+bytes.length+")");
        check(trace.contains("--- Sockets open at end ---")&&trace.indexOf("End: ")>trace.indexOf("--- Sockets open at end ---"),"Summary survives the change budget");

        script=new Script(idle);
        Thread.currentThread().interrupt();
        try {
            trace=ControlTrace.capture(script,script,12_000,50);
            check(trace.contains("; interrupted")&&trace.contains("samples=0;")&&Thread.currentThread().isInterrupted(),"Interruption stops sampling and keeps cancellation");
        } finally {Thread.interrupted();}

        ControlTrace.Clock slow=new ControlTrace.Clock(){
            long nanos;
            public long nanos(){long value=nanos;nanos+=80_000_000L;return value;}
            public long wallMs(){return nanos/1_000_000L;}
            public void sleep(long millis){nanos+=millis*1_000_000L;}
        };
        trace=ControlTrace.capture(new Script(idle),slow,1000,50);
        check(trace.contains("late_samples=")&&!trace.contains("late_samples=0;"),"Slow reads are reported instead of silently stretching the interval");
        System.out.println("PASS control trace assertions: "+checks);
    }
}
