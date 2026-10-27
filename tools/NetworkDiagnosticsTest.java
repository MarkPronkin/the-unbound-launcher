package dev.beampro.settings;

import java.util.*;

public final class NetworkDiagnosticsTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static String child(long timeout, String mode) throws Exception {
        return NetworkDiagnostics.run(timeout, System.getProperty("java.home") + "/bin/java", "-cp",
                System.getProperty("java.class.path"), NetworkDiagnosticsTest.class.getName(), mode);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            if (args[0].equals("slow")) { System.out.println("before timeout"); Thread.sleep(10000); }
            else if (args[0].equals("noisy")) { for (int i=0;i<20000;i++) System.out.print("network-data"); }
            else { System.err.println("bind: No such device"); System.exit(7); }
            return;
        }
        List<String> commands = new ArrayList<>();
        String report = NetworkDiagnostics.capture(true, (timeout, command) -> {
            commands.add(String.join(" ", command));
            if (commands.size()==1) throw new java.io.IOException("missing ip utility");
            return "exit=1; timedOut=false\nstderr: unreachable\n";
        });
        check(commands.size()==4 && commands.get(0).endsWith("ip -br address"), "Collect interface addresses");
        check(commands.get(1).endsWith("ip route show table all"), "Collect routes across Android tables");
        check(commands.get(2).endsWith("dumpsys -t 2 ethernet"), "Bound the Ethernet service dump");
        check(commands.get(3).endsWith("ping -I eth0 -c 3 -W 1 169.254.2.1"), "Probe the same interface and address as the SDK");
        check(report.contains("missing ip utility") && report.contains("stderr: unreachable"), "A missing utility preserves later probe results");
        commands.clear();
        report = NetworkDiagnostics.capture(false, (timeout, command) -> { commands.add(command[0]); return "app result"; });
        check(commands.size()==1 && report.contains("app UID"), "Keep a separate app-identity reachability result");
        report = child(3000,"failure");
        check(report.contains("exit=7") && report.contains("bind: No such device"), "Preserve exit status and stderr");
        long start = System.nanoTime(); report=child(200,"slow");
        check(report.contains("timedOut=true") && (System.nanoTime()-start)/1000000<2500, "A hung probe is killed within its deadline");
        report=child(3000,"noisy");
        check(report.contains("exit=0") && report.contains("output limited") && report.length()<8500, "Drain noisy output while retaining a bounded report");
        report=NetworkDiagnostics.capture(true,(timeout,command)->{throw new SecurityException("denied");});
        check(report.contains("denied") && report.contains("169.254.2.1"), "Permission failures remain exportable diagnostic evidence");
        System.out.println("PASS network diagnostics assertions: "+checks);
    }
}
