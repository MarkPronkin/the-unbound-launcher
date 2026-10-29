package dev.beampro.settings;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fixed, read-only display dumps. Large results travel over a pipe, never a Binder string. */
public final class DisplayDiagnostics {
    public static final int TRANSACTION = 0x00FFFFF3;
    public static final String TOKEN = "dev.beampro.bridge.DISPLAY_DIAGNOSTICS";
    public static final int DISPLAY = 0, SURFACE_FLINGER = 1, DISPLAY_LINK = 2, GLASSES_CONTROL = 3;
    public static final int MAX_BYTES = 1_010_000;
    public static final int MAX_LINK_BYTES = 200_000;
    public static final int MAX_CONTROL_BYTES = 32_000;
    private static final long CONTROL_BUDGET_MS = 4000;
    private static final int OUTPUT_BYTES = 1_000_000;
    interface Runner { String run(long timeout, String... command) throws Exception; }
    private static final Pattern LINK_LINE = Pattern.compile(
            "display|displayport|edid|\\bdrm\\b|\\bdpu\\b|\\bhpd\\b|\\bhdcp\\b|\\bucsi\\b|type.?c|alt.?mode|link.?train|dp[_ -](aux|link|ctrl)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GLASSES_PEER = Pattern.compile("(?:^|\\s)169\\.254\\.(?:1|2)\\.1(?:%eth[01])?(?::[0-9*]+)?(?:\\s|$)");
    private static final Pattern COUNTER = Pattern.compile("(?m)^(eth[01]\\.(?:rx|tx)_(?:bytes|packets|errors|dropped))=([0-9]+)$");
    private static final String CONTROL_COUNTERS = String.join("\n",
            "for iface in eth0 eth1; do",
            "  printf '\\nInterface: %s\\n' \"$iface\"",
            "  if [ ! -d \"/sys/class/net/$iface\" ]; then echo 'Interface unavailable in sysfs'; continue; fi",
            "  for key in operstate carrier flags mtu ifindex statistics/rx_bytes statistics/rx_packets statistics/rx_errors statistics/rx_dropped statistics/tx_bytes statistics/tx_packets statistics/tx_errors statistics/tx_dropped; do",
            "    name=${key##*/}",
            "    printf '%s.%s=' \"$iface\" \"$name\"",
            "    /system/bin/timeout -s KILL 1 /system/bin/head -c 96 \"/sys/class/net/$iface/$key\" 2>&1",
            "    printf '\\n'",
            "  done",
            "done");
    // No caller-controlled paths or shell text. Each sysfs read has its own
    // deadline as well as the whole subprocess deadline. Missing/restricted
    // connector interfaces are evidence, rather than a reason to fail export.
    private static final String CONNECTORS = String.join("\n",
            "count=0",
            "for node in /sys/class/drm/card*-DP-* /sys/class/drm/card*-HDMI-A-* /sys/class/typec/port*; do",
            "  [ -d \"$node\" ] || continue",
            "  count=$((count+1)); [ \"$count\" -le 12 ] || { echo '[connector count limited]'; break; }",
            "  printf '\\nConnector: %s\\n' \"$node\"",
            "  for attr in status enabled modes dpms link_status data_role power_role port_type power_operation_mode orientation svid mode active vdo displayport/configuration displayport/pin_assignment displayport/hpd; do",
            "    [ -e \"$node/$attr\" ] || continue",
            "    printf '%s: ' \"$attr\"",
            "    /system/bin/timeout -s KILL 1 /system/bin/head -c 2048 \"$node/$attr\" 2>&1",
            "    printf '\\n'",
            "  done",
            "  if [ -e \"$node/edid\" ]; then",
            "    echo 'EDID hex (first 512 bytes):'",
            "    /system/bin/timeout -s KILL 1 /system/bin/od -An -tx1 -N512 \"$node/edid\" 2>&1",
            "  fi",
            "done",
            "[ \"$count\" -gt 0 ] || echo 'No readable DRM/Type-C connector directories exposed to shell.'");

    public static String service(int kind) {
        switch (kind) {
            case DISPLAY: return "display";
            case SURFACE_FLINGER: return "SurfaceFlinger";
            case DISPLAY_LINK: return "USB/DisplayPort link";
            case GLASSES_CONTROL: return "Glasses control channel";
            default: throw new IllegalArgumentException("Unknown display diagnostic");
        }
    }

    public static String capture(int kind) {
        return capture(kind, (timeout, command) -> kind == DISPLAY_LINK
                && (command[0].endsWith("logcat") || command[0].endsWith("dmesg"))
                ? NetworkDiagnostics.runTailLimited(32_000, timeout, command)
                : NetworkDiagnostics.runLimited(kind == GLASSES_CONTROL ? 4096 : kind == DISPLAY_LINK ? 32_000 : OUTPUT_BYTES, timeout, command));
    }

    static String capture(int kind, Runner runner) {
        String service = service(kind);
        StringBuilder out = new StringBuilder("Captured: " + new Date() + "\nIdentity: ADB shell helper\n");
        if (kind == GLASSES_CONTROL) return captureControl(out,runner);
        if (kind == DISPLAY_LINK) {
            out.append("Fixed read-only probes; unavailable or permission-denied sources remain in this report.\n");
            probe(out, runner, "USB ports and DisplayPort Alt Mode", false, 4000,
                    "/system/bin/dumpsys", "-t", "3", "usb");
            probe(out, runner, "External display windows", false, 3000,
                    "/system/bin/dumpsys", "-t", "2", "window", "displays");
            probe(out, runner, "Recent Android display/USB/composer logs (fixed tags only)", false, 3000,
                    "/system/bin/logcat", "-d", "-b", "main", "-b", "system", "-v", "threadtime", "-t", "2500",
                    "*:S", "DisplayManagerService:V", "LocalDisplayAdapter:V", "DisplayDeviceRepository:V",
                    "LogicalDisplayMapper:V", "ExternalDisplayPolicy:V", "DisplayModeDirector:V", "SurfaceFlinger:V",
                    "UsbPortManager:V", "UsbDeviceManager:V", "UsbHostManager:V", "UsbService:V",
                    "hwc-display:V", "hwc-displayport:V", "hwc-drm:V", "HWC2:V", "ComposerHal:V");
            probe(out, runner, "Kernel display/link logs, when Android exposes the kernel buffer", true, 2000,
                    "/system/bin/logcat", "-d", "-b", "kernel", "-v", "threadtime", "-t", "2000");
            probe(out, runner, "Kernel display/link messages, when dmesg is permitted", true, 2000,
                    "/system/bin/dmesg");
            probe(out, runner, "DRM/Type-C connector attributes and EDID (fixed sysfs paths)", false, 3000,
                    "/system/bin/sh", "-c", CONNECTORS);
            probe(out, runner, "Helper temporary display-policy recovery state", false, 1000,
                    "/system/bin/head", "-c", "4096", "/data/local/tmp/unbound-launcher/display-policy.properties");
            return out.toString();
        }
        out.append("/system/bin/dumpsys -t 8 ").append(service).append('\n');
        try { out.append(runner.run(10_000, "/system/bin/dumpsys", "-t", "8", service)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); out.append("Interrupted\n"); }
        catch (Exception failure) { out.append("Unavailable: ").append(failure).append('\n'); }
        return out.toString();
    }

    private static String captureControl(StringBuilder out, Runner runner) {
        long started=System.nanoTime(),deadline=started+CONTROL_BUDGET_MS*1_000_000L;
        out.append("Service: Glasses control channel\n");
        out.append("Wall clock milliseconds: ").append(System.currentTimeMillis()).append("\nMonotonic nanoseconds: ").append(started).append('\n');
        out.append("Fixed read-only probes; no network connections or SDK commands are initiated.\n");
        out.append("Only connected TCP/UDP sockets with fixed glasses peers are included; unconnected UDP sockets, other namespaces and restricted netlink data may be invisible.\n");
        out.append("Routes are lookups for shell UID, not proof of the app socket route. Interface counters include all interface traffic. Socket state and counter changes do not prove request delivery or firmware receipt.\n");
        String before=controlProbe(out,runner,"Interface link state and counters BEFORE",false,650,deadline,
                "/system/bin/sh","-c",CONTROL_COUNTERS);
        for (String peer:new String[]{"169.254.2.1","169.254.1.1"})
            controlProbe(out,runner,"TCP/UDP sockets to "+peer,true,700,deadline,
                    "/system/bin/ss","-H","-n","-4","-t","-u","-a","dst",peer);
        for (String peer:new String[]{"169.254.2.1","169.254.1.1"})
            controlProbe(out,runner,"Shell route lookup for "+peer,false,450,deadline,
                    "/system/bin/ip","route","get",peer);
        String after=controlProbe(out,runner,"Interface link state and counters AFTER",false,650,deadline,
                "/system/bin/sh","-c",CONTROL_COUNTERS);
        Map<String,Long> first=counters(before),last=counters(after);
        out.append("\n--- Interface counter deltas (AFTER minus BEFORE) ---\n");
        int deltas=0;
        for (Map.Entry<String,Long> entry:first.entrySet()) {
            Long value=last.get(entry.getKey());if(value==null)continue;
            out.append(entry.getKey()).append('=');
            if(value>=entry.getValue())out.append(value-entry.getValue());
            else out.append("counter decreased or reset");
            out.append('\n');deltas++;
        }
        if(deltas==0)out.append("Counter deltas unavailable; inspect interface absence, permission errors or skipped probes above.\n");
        out.append("Capture finished wall_ms=").append(System.currentTimeMillis()).append("; monotonic_ns=").append(System.nanoTime())
                .append("; duration_ms=").append((System.nanoTime()-started)/1_000_000L).append('\n');
        return out.toString();
    }

    private static String controlProbe(StringBuilder out, Runner runner, String title, boolean sockets, long timeout, long deadline, String... command) {
        out.append("\n--- ").append(title).append(" ---\n");
        if (!command[0].endsWith("/sh"))out.append(String.join(" ",command)).append('\n');
        long start=System.nanoTime(),remaining=(deadline-start)/1_000_000L;
        out.append("Probe start wall_ms=").append(System.currentTimeMillis()).append("; monotonic_ns=").append(start).append('\n');
        String result;
        if(Thread.currentThread().isInterrupted())result="Interrupted; probe skipped\n";
        else if(remaining<=0)result="Capture deadline reached; probe skipped\n";
        else try { result=runner.run(Math.min(timeout,remaining),command); }
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();result="Interrupted\n";}
        catch(Exception failure){result="Unavailable: "+failure+'\n';}
        out.append(sockets?controlSocketLines(result):result);
        out.append("Probe end monotonic_ns=").append(System.nanoTime()).append("; duration_ms=").append((System.nanoTime()-start)/1_000_000L).append('\n');
        return result;
    }

    static String controlSocketLines(String report) {
        StringBuilder out=new StringBuilder();int matched=0;
        for(String line:report.split("\n")) {
            String lower=line.toLowerCase(java.util.Locale.ROOT);
            boolean status=line.startsWith("exit=")||line.startsWith("[output ")||line.startsWith("Unavailable:")
                    ||line.startsWith("Interrupted")||line.startsWith("Capture deadline")||lower.contains("cannot open netlink")
                    ||lower.contains("permission denied")||lower.contains("operation not permitted")||lower.startsWith("ss:")
                    ||lower.contains("no such file or directory");
            if(status)out.append(line).append('\n');
            else {
                String[] fields=line.trim().split("\\s+");
                // -t -u emits netid/state/recv-q/send-q/local/peer. Do not
                // retain other peer endpoints or optional process annotations.
                if(fields.length>=6&&(fields[0].equals("tcp")||fields[0].equals("udp"))&&GLASSES_PEER.matcher(fields[5]).matches()) {
                    out.append(String.join(" ",Arrays.copyOf(fields,6))).append('\n');matched++;
                }
            }
        }
        if(matched==0)out.append("[No visible connected TCP/UDP sockets to the fixed glasses peers; empty or restricted output cannot establish absence.]\n");
        return out.toString();
    }

    private static Map<String,Long> counters(String report) {
        Map<String,Long> values=new LinkedHashMap<>();Matcher matcher=COUNTER.matcher(report);
        while(matcher.find())try{values.put(matcher.group(1),Long.parseLong(matcher.group(2)));}
        catch(NumberFormatException tooLarge){ /* Raw counter remains available above. */ }
        return values;
    }

    private static void probe(StringBuilder out, Runner runner, String title, boolean filter, long timeout, String... command) {
        out.append("\n--- ").append(title).append(" ---\n");
        if (!command[0].endsWith("/sh")) out.append(String.join(" ", command)).append('\n');
        if (Thread.currentThread().isInterrupted()) { out.append("Interrupted; probe skipped\n"); return; }
        try {
            String result = runner.run(timeout, command);
            out.append(filter ? linkLines(result) : result);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); out.append("Interrupted\n"); }
        catch (Exception failure) { out.append("Unavailable: ").append(failure).append('\n'); }
    }

    static String linkLines(String report) {
        StringBuilder out = new StringBuilder();
        int matched = 0;
        for (String line : report.split("\n")) {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            boolean status = line.startsWith("exit=") || line.startsWith("[output ")
                    || line.startsWith("logcat:") || line.startsWith("dmesg:")
                    || lower.contains("permission denied") || lower.contains("permission denial")
                    || lower.contains("operation not permitted") || lower.contains("unable to open log device")
                    || lower.contains("unexpected eof") || lower.contains("unknown buffer") || lower.contains("no such file");
            if (status || LINK_LINE.matcher(line).find()) { out.append(line).append('\n'); if (!status) matched++; }
        }
        if (matched == 0) out.append("[No display/link messages in the available bounded kernel output.]\n");
        return out.toString();
    }

    /** Enforce a byte budget even for malformed UTF-8 and diagnostic error messages. */
    public static byte[] encode(String report) {
        return encode(report, MAX_BYTES);
    }

    public static byte[] encode(int kind, String report) {
        service(kind);
        return encode(report, kind == GLASSES_CONTROL ? MAX_CONTROL_BYTES : kind == DISPLAY_LINK ? MAX_LINK_BYTES : MAX_BYTES);
    }

    private static byte[] encode(String report, int limit) {
        byte[] bytes = report.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= limit) return bytes;
        byte[] marker = "\n[diagnostic file truncated at its byte limit]\n".getBytes(StandardCharsets.UTF_8);
        int keep = limit - marker.length;
        while (keep > 0 && (bytes[keep] & 0xC0) == 0x80) keep--;
        byte[] result = Arrays.copyOf(bytes, keep + marker.length);
        System.arraycopy(marker, 0, result, keep, marker.length);
        return result;
    }

    private DisplayDiagnostics() { }
}
