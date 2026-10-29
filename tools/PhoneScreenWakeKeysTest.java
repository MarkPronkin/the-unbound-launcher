package dev.beampro.bridge;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Execute the real raw-key reader with an isolated getevent fixture. */
public final class PhoneScreenWakeKeysTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void write(FileOutputStream output, String line) throws Exception {
        output.write((line + "\n").getBytes(StandardCharsets.UTF_8)); output.flush();
    }

    public static void main(String[] args) throws Exception {
        String devices = "add device 1: /dev/input/event0\n  KEY (0001): KEY_VOLUMEUP KEY_VOLUMEDOWN\n"
                + "    KEY_VOLUMEUP\nadd device 2: /dev/input/event1\n  KEY (0001): BTN_GAMEPAD\n"
                + "add device 3: /dev/input/event2\n  KEY (0001): KEY_VOLUMEDOWN\n"
                + "add device 4: /untrusted/path\n  KEY (0001): KEY_VOLUMEUP\n";
        check(PhoneScreenWakeKeys.volumeDevices(devices).equals(Arrays.asList("/dev/input/event0", "/dev/input/event2")),
                "Only readable input-node paths with volume keys are selected, without duplicates");
        for (String prefix : new String[]{"", "[ 123.456789] ", "/dev/input/event0: "}) {
            for (String key : new String[]{"KEY_VOLUMEUP", "KEY_VOLUMEDOWN"}) {
                check(PhoneScreenWakeKeys.volumeReleased(prefix + "EV_KEY " + key + " UP"), "Volume release recognized");
                check(!PhoneScreenWakeKeys.volumeReleased(prefix + "EV_KEY " + key + " DOWN"), "Down does not wake mid-press");
                check(!PhoneScreenWakeKeys.volumeReleased(prefix + "EV_KEY " + key + " REPEAT"), "Repeat does not wake");
            }
        }
        for (String line : new String[]{"EV_KEY BTN_A UP", "EV_KEY KEY_BACK UP", "EV_ABS ABS_X 0000",
                "EV_SYN SYN_REPORT 0000", "KEY_VOLUMEUP UP", "", "could not open KEY_VOLUMEUP"})
            check(!PhoneScreenWakeKeys.volumeReleased(line), "Controller/touch/diagnostic input ignored: " + line);

        Path work = java.nio.file.Paths.get(args[0]);
        Path fifo = work.resolve("events");
        check(new ProcessBuilder("mkfifo", fifo.toString()).start().waitFor() == 0, "Fixture pipe created");
        Path listing = work.resolve("listing");
        Files.write(listing, "add device 1: /dev/input/event0\n  KEY (0001): KEY_VOLUMEUP KEY_VOLUMEDOWN\n".getBytes(StandardCharsets.UTF_8));
        Path executable = work.resolve("getevent");
        String script = "#!/bin/sh\nif [ \"$1\" = -lp ]; then exec cat '" + listing + "'; fi\nexec cat '" + fifo + "'\n";
        Files.write(executable, script.getBytes(StandardCharsets.UTF_8));
        check(executable.toFile().setExecutable(true), "Fixture is executable");
        for (String reason : new String[]{"volume", "reader-lost", "closed"}) {
            CountDownLatch wake = new CountDownLatch(1);
            PhoneScreenWakeKeys keys = PhoneScreenWakeKeys.start(wake::countDown, executable.toString());
            try (FileOutputStream output = new FileOutputStream(fifo.toFile())) {
                write(output, "EV_KEY BTN_A UP"); write(output, "EV_KEY KEY_VOLUMEUP DOWN");
                check(!wake.await(100, TimeUnit.MILLISECONDS), "Non-wake events leave the game/session alone");
                if (reason.equals("volume")) {
                    write(output, "EV_KEY KEY_VOLUMEDOWN UP");
                    check(wake.await(2, TimeUnit.SECONDS), "Volume wakes without a focused window");
                } else if (reason.equals("closed")) keys.close();
            }
            if (reason.equals("reader-lost")) check(wake.await(2, TimeUnit.SECONDS), "Lost recovery restores phone");
            if (reason.equals("closed")) check(!wake.await(100, TimeUnit.MILLISECONDS), "Intentional cleanup does not wake a later session");
            keys.close(); keys.close();
        }
        Files.write(listing, "add device 1: /dev/input/event0\n  KEY (0001): BTN_GAMEPAD\n".getBytes(StandardCharsets.UTF_8));
        try {
            PhoneScreenWakeKeys.start(() -> {}, executable.toString());
            throw new AssertionError("Missing volume keys must reject screen-off");
        } catch (IllegalStateException expected) { check(expected.getMessage().contains("No readable"), "Missing recovery explained"); }
        System.out.println("PASS focus-independent volume recovery, ignored gamepad input and reader cleanup");
    }
}
