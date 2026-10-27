package dev.beampro.settings;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class DisplayDiagnosticsTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            if (args[0].equals("slow")) { System.out.println("partial display dump"); Thread.sleep(10000); }
            else if (args[0].equals("noisy")) { byte[] block=new byte[8192];Arrays.fill(block,(byte)'x');for(int i=0;i<160;i++)System.out.write(block); }
            else { System.err.println("Permission Denial: cannot dump SurfaceFlinger"); System.exit(9); }
            return;
        }
        for (int kind : new int[]{DisplayDiagnostics.DISPLAY, DisplayDiagnostics.SURFACE_FLINGER}) {
            String report=DisplayDiagnostics.capture(kind,(timeout,command)->{
                check(timeout==10000,"process has a bounded deadline");
                check(Arrays.equals(command,new String[]{"/system/bin/dumpsys","-t","8",DisplayDiagnostics.service(kind)}),"only fixed read-only commands");
                return "exit=0; timedOut=false\nphysical modes and policy\n";
            });
            check(report.contains("Identity: ADB shell helper")&&report.contains("Captured:")&&report.contains("physical modes"),"capture records identity, timestamp and output");
        }
        try { DisplayDiagnostics.capture(2,(t,c)->{throw new AssertionError("invalid selector ran");});throw new AssertionError("accepted invalid service"); }
        catch(IllegalArgumentException expected){checks++;}
        String report=DisplayDiagnostics.capture(0,(t,c)->{throw new java.io.IOException("missing dumpsys");});
        check(report.contains("Unavailable:")&&report.contains("missing dumpsys"),"missing commands remain diagnostic evidence");
        String java=System.getProperty("java.home")+"/bin/java", cp=System.getProperty("java.class.path");
        report=NetworkDiagnostics.runLimited(1_000_000,5000,java,"-cp",cp,DisplayDiagnosticsTest.class.getName(),"noisy");
        check(report.contains("exit=0")&&report.contains("output limited to 1000000 bytes")&&report.length()<1_000_200,"large stdout drains without blocking and reports truncation");
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
        System.out.println("PASS display diagnostic assertions: "+checks);
    }
}
