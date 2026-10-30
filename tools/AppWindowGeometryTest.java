package dev.beampro.bridge;

import android.view.Display;

public final class AppWindowGeometryTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    static String header(Display display, int fixed) {
        return "  DisplayDeviceInfo{\"" + display.name + "\": uniqueId=\"" + display.unique
                + "\", 1920 x 1080, touch VIRTUAL, rotation " + fixed
                + ", type VIRTUAL, owner com.android.shell (uid 2000), FLAG_ROTATES_WITH_CONTENT}";
    }
    static Display display(int width, int height, int logical, int fixed) {
        Display display = new Display();
        display.mode = new Display.Mode(width, height);
        display.rotation = logical;
        if (((logical + fixed) & 1) == 0) { display.width = width; display.height = height; }
        else { display.width = height; display.height = width; }
        ShellCommand.output = header(display, fixed);
        return display;
    }
    public static void main(String[] args) {
        // Execute the actual adapter over every logical quarter turn, with both
        // reported window presets, and a quarter-turn device installation offset.
        for (int fixed : new int[]{0, 1, 3}) for (int logical = 0; logical < 4; logical++) {
            for (int[] size : new int[][]{{1920,1080}, {900,1200}}) {
                Display display = display(size[0], size[1], logical, fixed);
                int before = ShellCommand.calls;
                int bufferRotation = AppWindowGeometry.rotation(display);
                check(bufferRotation == ((logical + fixed) & 3), "compositor rotation");
                int naturalWidth = (bufferRotation & 1) == 0 ? display.width : display.height;
                int naturalHeight = (bufferRotation & 1) == 0 ? display.height : display.width;
                check(naturalWidth == size[0] && naturalHeight == size[1], "ActivityContent surface size");
                int viewWidth = (bufferRotation & 1) == 0 ? naturalWidth : naturalHeight;
                int viewHeight = (bufferRotation & 1) == 0 ? naturalHeight : naturalWidth;
                check(viewWidth == display.width && viewHeight == display.height, "Unity logical window size");
                check(AppWindowGeometry.rotation(display) == bufferRotation, "stable callback");
                check(ShellCommand.calls - before == (fixed == 0 ? 0 : 1), "one read per affected display");
            }
        }
        // The report switches 1920x1080 -> 900x1200 -> 1920x1080 at logical 90°.
        Display retroid = display(1920,1080,1,3);
        check(AppWindowGeometry.rotation(retroid) == 0, "Retroid landscape");
        int before = ShellCommand.calls;
        retroid.mode = new Display.Mode(900,1200); retroid.width = 900; retroid.height = 1200;
        check(AppWindowGeometry.rotation(retroid) == 0, "Retroid portrait resize");
        retroid.mode = new Display.Mode(1920,1080); retroid.width = 1920; retroid.height = 1080;
        check(AppWindowGeometry.rotation(retroid) == 0, "Retroid landscape restore");
        retroid.rotation = 3;
        check(AppWindowGeometry.rotation(retroid) == 2, "reverse orientation preserved");
        retroid.mode = new Display.Mode(1000,1000); retroid.width = 1000; retroid.height = 1000;
        check(AppWindowGeometry.rotation(retroid) == 2, "known device rotation survives a square resize");
        check(ShellCommand.calls == before, "resize reuses device offset");

        Display target = display(1920,1080,1,3);
        String valid = header(target,3);
        check(AppWindowGeometry.deviceRotation(valid,target.unique) == 3, "exact device identity");
        for (String invalid : new String[]{"", valid.replace(target.unique,target.unique + "-other"),
                valid.replace("type VIRTUAL", "type EXTERNAL"), valid.replace("uid 2000", "uid 10146"),
                valid.replace("FLAG_ROTATES_WITH_CONTENT", "FLAG_TRUSTED"),
                valid.replace("rotation 3", "rotation 7"), valid + "\n" + valid}) {
            check(AppWindowGeometry.deviceRotation(invalid,target.unique) == -1, "reject ambiguous device metadata");
        }
        check(AppWindowGeometry.deviceRotation(valid,null) == -1, "null identity");
        check(AppWindowGeometry.deviceRotation(valid,"local:22") == -1, "physical identity");
        for (String invalid : new String[]{"", valid.replace("rotation 3", "rotation 0"),
                valid.replace(target.unique,"virtual:other")}) {
            Display unavailable = display(1920,1080,1,3); ShellCommand.output = invalid;
            before = ShellCommand.calls;
            check(AppWindowGeometry.rotation(unavailable) == 1, "no rotation guessed");
            check(AppWindowGeometry.rotation(unavailable) == 1 && ShellCommand.calls == before + 1,
                    "unavailable metadata does not repeatedly block the display Looper");
        }
        for (int failure = 1; failure <= 3; failure++) {
            Display unavailable = display(1920,1080,1,3); ShellCommand.failure = failure;
            check(AppWindowGeometry.rotation(unavailable) == 1, "command failure preserves stock behavior");
            if (failure == 3) check(Thread.interrupted(), "interrupt preserved");
        }
        ShellCommand.failure = 0;
        Display other = display(1920,1080,1,3); other.name = "DP Screen";
        before = ShellCommand.calls;
        check(AppWindowGeometry.rotation(other) == 1 && ShellCommand.calls == before, "physical display untouched");
        Display square = display(1000,1000,1,3);
        check(AppWindowGeometry.rotation(square) == 1 && ShellCommand.calls == before, "square is ambiguous");
        Display resizing = display(1920,1080,1,3); resizing.width = 900; resizing.height = 1200;
        check(AppWindowGeometry.rotation(resizing) == 1 && ShellCommand.calls == before, "stale mode during resize");
        System.out.println("PASS app window geometry assertions: " + checks);
    }
}
