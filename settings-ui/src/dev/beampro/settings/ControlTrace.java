package dev.beampro.settings;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only kernel socket-table evidence for the two fixed glasses peers. A trace samples the
 * tables at a fixed interval and writes only changes, so a stalled control request shows whether
 * its bytes stayed unacknowledged (retransmit or zero-window timers) or arrived and were not read.
 */
public final class ControlTrace {
    public static final long DURATION_MS=12_000, INTERVAL_MS=50;
    public static final int MAX_BYTES=24_000;
    static final int TABLE_BYTES=1<<20, TABLE_PROBE_BYTES=4*TABLE_BYTES+4096;
    private static final int FOOTER_BYTES=4_000;
    static final String[] TABLES={"/proc/net/tcp","/proc/net/tcp6","/proc/net/udp","/proc/net/udp6"};
    static final String DEVICES="/proc/net/dev";
    private static final String[] INTERFACES={"eth0","eth1"};
    private static final String[] STATES={"UNKNOWN","ESTABLISHED","SYN_SENT","SYN_RECV","FIN_WAIT1","FIN_WAIT2","TIME_WAIT",
            "CLOSE","CLOSE_WAIT","LAST_ACK","LISTEN","CLOSING","NEW_SYN_RECV"};
    private static final String[] TIMERS={"none","retransmit","keepalive","time-wait","zero-window-probe"};
    // Linux prints IPv4 addresses as host-order words (little-endian on Android) and socket
    // timers in USER_HZ clock ticks, which is 100 on Android kernels.
    private static final String V4_MAPPED="0000000000000000FFFF0000";
    private static final long TICK_MS=10;

    interface Source { String read(String path) throws IOException; }
    interface Clock { long nanos(); long wallMs(); void sleep(long millis) throws InterruptedException; }

    static final Source FILES=ControlTrace::readFile;
    static final Clock SYSTEM=new Clock() {
        public long nanos(){return System.nanoTime();}
        public long wallMs(){return System.currentTimeMillis();}
        public void sleep(long millis) throws InterruptedException {Thread.sleep(millis);}
    };

    static final class Socket {
        final String protocol,local,peer;
        final long inode,uid,sendQueue,receiveQueue,timerMs,retransmits,rtoMs,drops;
        final int state,timer,probes,cwnd,ssthresh;
        Socket(String protocol,String local,String peer,long inode,long uid,int state,long sendQueue,long receiveQueue,
               int timer,long timerMs,long retransmits,int probes,long rtoMs,int cwnd,int ssthresh,long drops) {
            this.protocol=protocol;this.local=local;this.peer=peer;this.inode=inode;this.uid=uid;this.state=state;
            this.sendQueue=sendQueue;this.receiveQueue=receiveQueue;this.timer=timer;this.timerMs=timerMs;
            this.retransmits=retransmits;this.probes=probes;this.rtoMs=rtoMs;this.cwnd=cwnd;this.ssthresh=ssthresh;this.drops=drops;
        }
        String key(){return protocol+' '+local+'>'+peer+" ino="+inode;}
        /** Remaining timer time, RTO and congestion window are shown, not compared; they move on every sample. */
        String change(){return state+":"+sendQueue+":"+receiveQueue+":"+timer+":"+retransmits+":"+probes+":"+drops;}
        String describe() {
            StringBuilder out=new StringBuilder(protocol).append(' ').append(name(STATES,state)).append(' ')
                    .append(local).append('>').append(peer).append(" uid=").append(uid).append(" ino=").append(inode)
                    .append(" sendq=").append(sendQueue).append(" recvq=").append(receiveQueue);
            if(protocol.startsWith("tcp")) {
                out.append(" timer=").append(name(TIMERS,timer));
                if(timer!=0)out.append('(').append(timerMs).append("ms)");
                out.append(" retrans=").append(retransmits).append(" probes=").append(probes);
                if(rtoMs>=0)out.append(" rto=").append(rtoMs).append("ms cwnd=").append(cwnd).append(" ssthresh=").append(ssthresh);
            } else out.append(" drops=").append(drops);
            return out.toString();
        }
    }

    /** Rows of one kernel table whose remote endpoint is a fixed glasses peer; malformed rows are skipped. */
    static List<Socket> parse(String table,String protocol) {
        List<Socket> sockets=new ArrayList<>();
        boolean tcp=protocol.startsWith("tcp");
        for(String line:table.split("\n")) {
            String[] f=line.trim().split("\\s+");
            if(f.length<10||!f[0].endsWith(":"))continue;
            try {
                String peer=address(f[2]);
                if(peer==null||!(peer.startsWith("169.254.2.1:")||peer.startsWith("169.254.1.1:")))continue;
                String local=address(f[1]);
                String[] queues=f[4].split(":"),timers=f[5].split(":");
                // TIME_WAIT and request-socket rows stop after the inode/reference/pointer fields.
                boolean full=tcp&&f.length>=17;
                sockets.add(new Socket(protocol,local==null?"?":local,peer,Long.parseLong(f[9]),Long.parseLong(f[7]),
                        Integer.parseInt(f[3],16),Long.parseLong(queues[0],16),Long.parseLong(queues[1],16),
                        Integer.parseInt(timers[0],16),Long.parseUnsignedLong(timers[1],16)*TICK_MS,Long.parseLong(f[6],16),
                        Integer.parseInt(f[8]),full?Long.parseLong(f[12])*TICK_MS:-1,full?Integer.parseInt(f[15]):0,
                        full?Integer.parseInt(f[16]):0,!tcp&&f.length>=13?Long.parseLong(f[12]):0));
            } catch(RuntimeException malformed) { /* Not a socket row in the documented format. */ }
        }
        return sockets;
    }

    static String address(String token) {
        int colon=token.indexOf(':');
        if(colon<0)return null;
        String host=token.substring(0,colon).toUpperCase(Locale.ROOT);
        if(host.length()==32) {
            if(!host.startsWith(V4_MAPPED))return null;
            host=host.substring(24);
        }
        if(host.length()!=8)return null;
        long value=Long.parseLong(host,16);
        return (value&255)+"."+(value>>8&255)+"."+(value>>16&255)+"."+(value>>24&255)+":"+Integer.parseInt(token.substring(colon+1),16);
    }

    /** eth0/eth1 rows of /proc/net/dev, keyed like the sysfs statistics files. */
    static Map<String,Long> deviceCounters(String text) {
        Map<String,Long> values=new LinkedHashMap<>();
        String[][] fields={{"rx_bytes","0"},{"rx_packets","1"},{"rx_errors","2"},{"rx_dropped","3"},
                {"tx_bytes","8"},{"tx_packets","9"},{"tx_errors","10"},{"tx_dropped","11"}};
        for(String line:text.split("\n")) {
            int colon=line.indexOf(':');
            if(colon<0)continue;
            String name=line.substring(0,colon).trim();
            if(!name.equals("eth0")&&!name.equals("eth1"))continue;
            String[] f=line.substring(colon+1).trim().split("\\s+");
            if(f.length<12)continue;
            try { for(String[] field:fields)values.put(name+"."+field[0],Long.parseLong(f[Integer.parseInt(field[1])])); }
            catch(NumberFormatException malformed) { /* The raw row remains in the report. */ }
        }
        return values;
    }

    /** Decode a probe that prints "@@ <table>" before each kernel table. Errors stay readable. */
    static String tables(String report) {
        StringBuilder out=new StringBuilder(),table=new StringBuilder();
        String protocol=null;int matched=0;
        for(String line:(report+"\n@@ end").split("\n")) {
            if(line.startsWith("@@ ")) {
                if(protocol!=null)for(Socket socket:parse(table.toString(),protocol)){out.append(socket.describe()).append('\n');matched++;}
                String path=line.substring(3).trim();
                protocol=path.startsWith("/proc/net/")?path.substring(10):null;table.setLength(0);
                continue;
            }
            String lower=line.toLowerCase(Locale.ROOT);
            if(line.startsWith("exit=")||line.startsWith("[output ")||lower.contains("denied")||lower.contains("not permitted")
                    ||lower.contains("no such file")||lower.startsWith("head:")||lower.startsWith("timeout:"))out.append(line).append('\n');
            else table.append(line).append('\n');
        }
        if(matched==0)out.append("[No fixed-peer rows in the readable kernel socket tables.]\n");
        return out.toString();
    }

    static String capture(Source source,Clock clock,long durationMs,long intervalMs) {
        long started=clock.nanos(),wallStart=clock.wallMs(),deadline=started+durationMs*1_000_000L;
        StringBuilder out=new StringBuilder("Captured: ").append(new Date(wallStart)).append("\nIdentity: ADB shell helper\nService: Glasses control trace\n");
        out.append("Samples ").append(String.join(", ",TABLES)).append(" every ").append(intervalMs).append(" ms for ").append(durationMs)
                .append(" ms. A line is written only when a socket to 169.254.2.1 or 169.254.1.1 appears, changes or closes. Read-only; no network connections or SDK commands are initiated.\n");
        out.append("sendq: TCP bytes written but not yet acknowledged by the glasses, including unsent bytes. recvq: bytes received from the glasses but not yet read by the owning app (compare uid with App UID in device-and-session.txt). timer=retransmit: the phone is waiting for an acknowledgement; timer=zero-window-probe: the glasses advertised no receive space. retrans/probes count consecutive unanswered attempts. Interface counters are packets/dropped since boot.\n");
        out.append("Limits: unconnected UDP sockets, other network namespaces and changes shorter than the sampling interval are invisible. Socket state does not identify which request the firmware received.\n");
        out.append("Start: ").append(time(wallStart)).append("; wall_ms=").append(wallStart).append("; monotonic_ns=").append(started).append('\n');
        Map<String,Long> failures=new LinkedHashMap<>();Map<String,String> firstFailure=new LinkedHashMap<>();
        Map<String,Long> before=counters(source,failures,firstFailure);
        out.append("Interface counters at start: ").append(link(before).isEmpty()?"unavailable":link(before)).append('\n');
        out.append("\n--- Changes ---\n");
        int budget=MAX_BYTES-FOOTER_BYTES-out.toString().getBytes(StandardCharsets.UTF_8).length,used=0;
        Map<String,Socket> live=new LinkedHashMap<>();
        long samples=0,changes=0,omitted=0,late=0,next=started;boolean interrupted=false;
        while(true) {
            if(Thread.currentThread().isInterrupted()){interrupted=true;break;}
            long now=clock.nanos();
            Map<String,Socket> current=new LinkedHashMap<>();
            for(String table:TABLES) {
                try { for(Socket socket:parse(source.read(table),table.substring(10)))current.put(socket.key(),socket); }
                catch(IOException|RuntimeException failure){fail(failures,firstFailure,table,failure);}
            }
            samples++;
            List<String> lines=new ArrayList<>();
            for(Socket socket:current.values()) {
                Socket previous=live.get(socket.key());
                if(previous==null)lines.add("NEW "+socket.describe());
                else if(!previous.change().equals(socket.change()))lines.add(socket.describe());
            }
            for(Socket socket:live.values())if(!current.containsKey(socket.key()))lines.add("GONE "+socket.describe());
            live=current;
            if(!lines.isEmpty()) {
                String counters=link(counters(source,failures,firstFailure));
                String stamp=time(clock.wallMs())+" +"+(now-started)/1_000_000L+"ms ";
                for(String line:lines) {
                    changes++;
                    String framed=stamp+line+(counters.isEmpty()?"":" | "+counters)+"\n";
                    int size=framed.getBytes(StandardCharsets.UTF_8).length;
                    if(used+size<=budget){out.append(framed);used+=size;}else omitted++;
                }
            }
            long after=clock.nanos();
            if(after>=deadline)break;
            next+=intervalMs*1_000_000L;
            if(next<after){late++;next=after;}
            try{clock.sleep(Math.max(1,(Math.min(next,deadline)-after)/1_000_000L));}
            catch(InterruptedException stopped){Thread.currentThread().interrupt();interrupted=true;break;}
        }
        if(changes==0)out.append("[No socket to the fixed glasses peers appeared or changed during the trace.]\n");
        out.append("\n--- Sockets open at end ---\n");
        if(live.isEmpty())out.append("none\n");
        for(Socket socket:live.values())out.append(socket.describe()).append('\n');
        long ended=clock.nanos(),wallEnd=clock.wallMs();
        Map<String,Long> after=counters(source,failures,firstFailure);
        out.append("Interface counters at end: ").append(link(after).isEmpty()?"unavailable":link(after)).append('\n');
        StringBuilder deltas=new StringBuilder();
        for(Map.Entry<String,Long> entry:before.entrySet()) {
            Long value=after.get(entry.getKey());
            if(value==null||!entry.getKey().endsWith("packets")&&!entry.getKey().endsWith("dropped")&&!entry.getKey().endsWith("errors"))continue;
            deltas.append(deltas.length()==0?"":" ").append(entry.getKey()).append('=').append(value>=entry.getValue()?String.valueOf(value-entry.getValue()):"reset");
        }
        out.append("Interface counter deltas: ").append(deltas.length()==0?"unavailable":deltas).append('\n');
        for(Map.Entry<String,Long> entry:failures.entrySet())
            out.append("Read failures for ").append(entry.getKey()).append(": ").append(entry.getValue()).append("; first: ").append(firstFailure.get(entry.getKey())).append('\n');
        out.append("End: ").append(time(wallEnd)).append("; samples=").append(samples).append("; changes=").append(changes)
                .append("; lines_omitted_at_byte_limit=").append(omitted).append("; late_samples=").append(late)
                .append("; duration_ms=").append((ended-started)/1_000_000L).append(interrupted?"; interrupted":"").append('\n');
        return out.toString();
    }

    private static Map<String,Long> counters(Source source,Map<String,Long> failures,Map<String,String> first) {
        try { return deviceCounters(source.read(DEVICES)); }
        catch(IOException|RuntimeException failure){fail(failures,first,DEVICES,failure);return new LinkedHashMap<>();}
    }

    private static String link(Map<String,Long> counters) {
        StringBuilder out=new StringBuilder();
        for(String name:INTERFACES) {
            Long rx=counters.get(name+".rx_packets"),tx=counters.get(name+".tx_packets");
            if(rx==null||tx==null)continue;
            out.append(out.length()==0?"":" ").append(name).append(" rx=").append(rx).append("p/").append(counters.get(name+".rx_dropped"))
                    .append("d tx=").append(tx).append("p/").append(counters.get(name+".tx_dropped")).append('d');
        }
        return out.toString();
    }

    private static void fail(Map<String,Long> failures,Map<String,String> first,String path,Exception failure) {
        failures.put(path,failures.getOrDefault(path,0L)+1);
        first.putIfAbsent(path,String.valueOf(failure).replace('\n',' '));
    }

    private static String name(String[] names,int value){return value>=0&&value<names.length?names[value]:"0x"+Integer.toHexString(value);}
    private static String time(long wallMs){return new SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(new Date(wallMs));}

    static String readFile(String path) throws IOException {
        try(InputStream input=new FileInputStream(path);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[16_384];int count;
            while((count=input.read(buffer,0,Math.min(buffer.length,TABLE_BYTES-output.size())))>0)output.write(buffer,0,count);
            return new String(output.toByteArray(),StandardCharsets.UTF_8);
        }
    }

    private ControlTrace() { }
}
