package dev.beampro.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Looper;
import android.view.Display;
import java.lang.reflect.Method;

/** Execute the patched ActivityContent with Android's real display and surface APIs. */
public final class AppWindowGeometryDeviceChecks {
    public static void main(String[] args) throws Exception {
        java.io.PrintStream output = new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),true);
        try { run(args,output);System.exit(0); }
        catch (Throwable failure) {failure.printStackTrace(output);System.exit(1);}
    }
    static void run(String[] args,java.io.PrintStream output) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new SecurityException("ADB shell fixture only");
        Looper.prepareMainLooper();
        Class<?> threadType = Class.forName("android.app.ActivityThread");
        Object thread = threadType.getMethod("systemMain").invoke(null);
        Context context = ((Context) threadType.getMethod("getSystemContext").invoke(thread))
                .createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        System.setOut(output);
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        Class<?> type = Class.forName("ai.nreal.nebula.service.ActivityContent");
        Class<?> listener = Class.forName("ai.nreal.nebula.service.ActivityContent$OnSurfaceUpdateListener");
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(ComponentName.class, listener);
        constructor.setAccessible(true);
        Method attach = method(type,"setVirtualDisplay",VirtualDisplay.class);
        Method update = method(type,"updateSize");
        Method width = method(type,"getLastWidth"), height = method(type,"getLastHeight");
        Method rotation = method(type,"getRotation");
        int checks = 0;
        int[] variants = args.length > 0 && args[0].equals("fixed") ? new int[]{1481,1483} : new int[]{1481};
        for (int flags : variants) for (int[] size : new int[][]{{1920,1080},{900,1200}}) {
            ImageReader reader = ImageReader.newInstance(size[0],size[1],PixelFormat.RGBA_8888,2);
            VirtualDisplay virtual = null;
            try {
                virtual = manager.createVirtualDisplay("com.xreal.virtualdisplay-fixture-" + flags,
                        size[0],size[1],300,reader.getSurface(),flags);
                if (virtual == null) throw new AssertionError("Virtual display not created");
                Object content = constructor.newInstance(new ComponentName("com.android.shell","Fixture"),null);
                attach.invoke(content,virtual);
                update.invoke(content);
                Display display = virtual.getDisplay();
                Point logical = new Point(); display.getRealSize(logical);
                int bufferRotation = (Integer) rotation.invoke(content);
                if ((Integer) width.invoke(content) != size[0] || (Integer) height.invoke(content) != size[1])
                    throw new AssertionError("Wrong surface size, flags=" + flags + " logical=" + logical);
                checks++;
                int viewWidth = (bufferRotation & 1) == 0 ? size[0] : size[1];
                int viewHeight = (bufferRotation & 1) == 0 ? size[1] : size[0];
                if (viewWidth != logical.x || viewHeight != logical.y)
                    throw new AssertionError("Window aspect differs from logical display");
                checks++;
                if (flags == 1483 && display.getRotation() == bufferRotation)
                    throw new AssertionError("Use persist.demo.remoterotation=portrait to exercise fixed rotation");
                if (flags == 1481 && display.getRotation() != bufferRotation)
                    throw new AssertionError("Ordinary display rotation changed");
                checks++;
                String unique = (String) Display.class.getMethod("getUniqueId").invoke(display);
                ShellCommand.Result dump = ShellCommand.run(5000,"dumpsys","-t","4","display");
                int expectedFixed = flags == 1483 ? 3 : 0;
                int parsed = AppWindowGeometry.deviceRotation(dump.output,unique);
                if (!dump.succeeded() || parsed != expectedFixed)
                    throw new AssertionError("Could not read the actual managed display's device rotation: exit="
                            + dump.exitCode + " timeout=" + dump.timedOut + " bytes=" + dump.output.length()
                            + " rotation=" + parsed);
                checks++;
                System.out.println("WINDOW_GEOMETRY flags=" + flags + " logical=" + logical.x + "x" + logical.y
                        + " surface=" + size[0] + "x" + size[1] + " raw=" + display.getRotation()
                        + " buffer=" + bufferRotation);
            } finally {
                if (virtual != null) virtual.release();
                reader.close();
            }
        }
        System.out.println("PASS actual app window geometry DEX assertions: " + checks);
    }
    static Method method(Class<?> type,String name,Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name,parameters);method.setAccessible(true);return method;
    }
}
