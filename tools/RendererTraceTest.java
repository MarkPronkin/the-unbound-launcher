package dev.beampro.settings;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class RendererTraceTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]); RendererTrace trace = new RendererTrace(root);
        check(trace.token().isEmpty(), "off by default");
        check(trace.report().contains("No renderer sample captured"), "missing evidence explicit");
        trace.start(true); String token = trace.token();
        String one = String.format(Locale.US, "%020d-100", 1);
        trace.append(token, one, "\n--- Renderer sample first ---\nmono viewport\n");
        for (int i = 0; i < 30; i++) trace.append(token, one, "\n--- Renderer sample " + i + " ---\n" + "matrix\n".repeat(2000));
        String report = new RendererTrace(root).report();
        check(report.contains("mono viewport") && report.contains("sample 29"), "first and latest survive restart and rotation");
        check(!report.contains("sample 10 ---"), "old recent snapshots rotate");
        trace.stop(); trace.append(token, one, "forbidden after stop\n");
        check(!trace.report().contains("forbidden"), "stopped collection rejects in-flight write");
        trace.start(false); String next = trace.token(); check(!next.equals(token), "new opt-in generation");
        trace.append(token, one, "stale-generation\n");
        check(!trace.report().contains("stale-generation"), "old Unity jobs cannot enter new collection");
        trace.reset(); check(trace.token().isEmpty() && !trace.report().contains("mono viewport"), "reset clears evidence without enabling collection");
        File orphan = new File(root, "renderer/session-00000000000000000001-99.txt.first");
        Files.writeString(orphan.toPath(), "interrupted first snapshot");
        File temporary = new File(root, "renderer/session-00000000000000000001-99.txt.tmp");
        Files.writeString(temporary.toPath(), "interrupted write");
        trace.start(false); token = trace.token();
        check(!orphan.exists() && !temporary.exists(), "restart cleans interrupted writes");
        for (int i = 0; i < 7; i++) trace.append(token, String.format(Locale.US, "%020d-100", i), "\n--- Renderer sample session" + i + " ---\n" + "ü".repeat(20_000));
        report = trace.report();
        check(!report.contains("session2 ---") && report.contains("session3 ---") && report.contains("session6 ---"), "four sessions retained");
        check(report.getBytes("UTF-8").length <= RendererTrace.MAX_BYTES, "bounded export");
        check(!report.contains("�"), "UTF-8 truncation stays valid");
        try { trace.append(token, "../escape", "bad"); throw new AssertionError("traversal accepted"); } catch (IOException expected) {}
        final String active = token;
        final String newest = String.format(Locale.US, "%020d-100", 8);
        Thread writer = new Thread(() -> { try { for(int i=0;i<50;i++)new RendererTrace(root).append(active, newest,"\n--- Renderer sample concurrent ---\n"); } catch(Exception e){throw new RuntimeException(e);} });
        writer.setUncaughtExceptionHandler((t,e)->{e.printStackTrace();System.exit(2);}); writer.start();
        for(int i=0;i<50;i++)new RendererTrace(root).report(); writer.join();
        check(trace.report().contains("concurrent"), "same-process instances serialize file locks");
        System.out.println("PASS renderer trace opt-in, stale jobs, rotation, restart, UTF-8, concurrent export");
    }
}
