package dev.beampro.settings;

import java.util.ArrayList;
import java.util.List;

/** Replays native -> detached -> VGA -> native output, including retries and capture storms. */
public final class DisplayTransitionsTest {
    private static int checks;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        List<String> events = new ArrayList<>(), captures = new ArrayList<>();
        DisplayTransitions trace = new DisplayTransitions(new DisplayTransitions.Sink() {
            public void event(String attempt, String detail) { events.add(attempt + " " + detail); }
            public void capture(String attempt, String reason) { captures.add(attempt + " " + reason); }
        });
        trace.sample(1, 0, "640x480", true, false, false);
        check(events.isEmpty() && captures.isEmpty(), "No collection outside an explicitly started attempt");
        trace.begin("one", 100);
        trace.sample(1000, 100, "7 1920x1080@120 supported=1920x1080", true, true, false);
        trace.sample(1100, 200, "no external physical display", false, false, false);
        check(captures.isEmpty(), "Transient removal during the stereo switch does not launch heavy dumps");
        trace.sample(1200, 300, "8 640x480@60 supported=640x480", true, false, false);
        check(captures.size() == 1 && captures.get(0).contains("nativeOutputPreviouslySeen=true"), "Capture the first physical fallback immediately after native mode loss");
        check(events.size() == 3 && events.get(2).contains("wallMs=1200; uptimeMs=300"), "Persist both clocks and every changed state independently of logcat");
        for (int i = 0; i < 1000; i++) trace.sample(1300+i, 400+i, "8 640x480@60 supported=640x480", true, false, false);
        check(events.size() == 3 && captures.size() == 1, "Repeated callbacks and polling do not flood logs or helper captures");
        trace.begin("one", 2000);
        trace.sample(3000, 3000, "9 640x480@60 supported=640x480", true, false, false);
        check(captures.size() == 1, "SDK retry inside one attempt does not reset the first-fallback limit");
        trace.sample(45_000, 45_000, "9 640x480@60 supported=640x480", true, false, true);
        check(captures.size() == 2 && captures.get(1).contains("Connection failure"), "Retain a later failure snapshot alongside the first fallback");
        trace.sample(46_000, 46_000, "10 3840x1080@60 supported=3840x1080", true, true, false);
        check(events.get(events.size()-1).contains("3840x1080") && captures.size() == 2, "Record recovery without another heavy dump");
        int before = events.size();
        trace.sample(100_000, 100_000, "disconnected", false, false, true);
        check(!trace.active(100_000) && events.size() == before, "Attempt observations stop after the bounded window");
        trace.begin("two", 101_000);
        trace.sample(101_001, 101_001, "11 640x480@60", true, false, false);
        check(captures.size() == 3 && captures.get(2).startsWith("two ") && captures.get(2).contains("nativeOutputPreviouslySeen=false"), "A new attempt gets a fresh capture and does not inherit native-mode evidence");
        trace.stop();
        trace.sample(102_000, 102_000, "12 1920x1080", true, true, true);
        check(captures.size() == 3 && !trace.active(102_000), "Stopping collection prevents further observations and captures");
        trace.begin("two", 103_000);
        trace.sample(103_001, 103_001, "no external physical display", false, false, true);
        check(captures.size() == 4, "An explicit failure still captures system evidence when the display has already disappeared");
        System.out.println("PASS display transition assertions: " + checks);
    }
}
