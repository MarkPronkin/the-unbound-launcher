package dev.beampro.renderfixture;

import android.app.*;
import android.content.*;
import android.os.*;
import dev.beampro.settings.RendererDiagnostics;
import dev.beampro.settings.RendererTrace;
import java.io.*;
import java.util.*;

/** Isolated emulator fixture: production Java collector and JNI library, synthetic Unity API data. */
public class RuntimeChecks extends Instrumentation {
    private static native int nativeCalls();
    private static native void nativeLayout(int mode);
    private RendererTrace trace;
    private int assertions;
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); assertions++; }
    private String await(String text, long timeout) throws Exception {
        long until = SystemClock.uptimeMillis() + timeout; String report;
        do { report = trace.report(); if (report.contains(text)) return report; Thread.sleep(100); } while (SystemClock.uptimeMillis() < until);
        throw new AssertionError("Missing " + text + "\n" + report);
    }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); HostActivity activity = null;
        try {
            System.loadLibrary("il2cpp");
            activity = (HostActivity) startActivitySync(new Intent(getTargetContext(), HostActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final HostActivity owner = activity;
            trace = new RendererTrace(new File(activity.getFilesDir(), "debug")); trace.reset();
            runOnMainSync(() -> RendererDiagnostics.track(owner)); Thread.sleep(2500);
            check(nativeCalls() == 0, "debug off makes no Unity calls");
            trace.start(true);
            String mono = await("GlassConfig.GlassMode=10", 6000);
            check(mono.contains("renderPassCount=1") && mono.contains("renderParameterCount=1"), "mono geometry");
            runOnMainSync(() -> nativeLayout(1));
            String stereo = await("textureArraySlice=1", 6000);
            check(stereo.contains("renderParameterCount=2") && stereo.contains("projectionScaleAspect="), "instanced stereo geometry and projection");
            runOnMainSync(() -> nativeLayout(2));
            String multipass = await("renderPassCount=2", 6000);
            check(multipass.contains("parameter=0"), "multipass stereo geometry");
            try (FileOutputStream out = new FileOutputStream(new File(activity.getFilesDir(), "renderer-fixture-samples.txt"))) {
                out.write(("MONO\n" + mono + "\nSTEREO\n" + stereo + "\nMULTIPASS\n" + multipass).getBytes("UTF-8"));
            }
            trace.stop(); int before = nativeCalls(); Thread.sleep(2500);
            check(nativeCalls() == before, "collection off stops Unity readback");
            runOnMainSync(() -> owner.mUnityPlayer.block = true);
            trace.start(true); await("has not responded for 5 seconds", 11000);
            check(owner.mUnityPlayer.queued.size() == 1, "stalled Unity queue is bounded");
            trace.stop(); trace.start(true); int stale = nativeCalls();
            runOnMainSync(() -> owner.mUnityPlayer.queued.remove(0).run());
            check(nativeCalls() == stale, "old opt-in generation rejected before native readback");
            check(!trace.report().contains("Renderer snapshot schema=1"), "old sample does not enter new collection");
            trace.stop();
            result.putString("result", "PASS " + assertions + " Android renderer collection assertions; synthetic Unity metadata, production collector and JNI library");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("error", failure.toString()); finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (activity != null) { final Activity owner = activity; runOnMainSync(owner::finish); }
        }
    }
    public static class HostActivity extends Activity {
        public final Player mUnityPlayer = new Player();
    }
    public static class Player {
        public volatile boolean block;
        public final List<Runnable> queued = new java.util.concurrent.CopyOnWriteArrayList<>();
        public void queueGLThreadEvent(Runnable runnable) {
            if (block) queued.add(runnable);
            else new Handler(Looper.getMainLooper()).post(runnable);
        }
    }
}
