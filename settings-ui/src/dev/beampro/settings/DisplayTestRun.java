package dev.beampro.settings;

/** Timed display experiment. A mode command succeeding is separate from observing native output. */
final class DisplayTestRun {
    enum Kind { TWO_D, SBS, RESTORE }
    enum Phase { STARTING, MONO, BASELINE, STEREO, PICTURE, RESTORING, DONE }
    interface Driver {
        void mode(boolean stereo, int request);
        void restore(int request);
        void capture(String stage);
        void pattern(boolean stereo);
        void status(String text);
        void complete(String text, boolean restored);
    }
    static final long START_MS=35_000, MODE_MS=12_000, PICTURE_MS=20_000, RESTORE_MS=20_000;
    final Kind kind;
    private final Driver driver;
    Phase phase=Phase.STARTING;
    private long deadline, stableSince=-1;
    private int stableDisplay=-1, request;
    private boolean accepted, stereoCaptured, restoreAccepted;
    private String outcome="Test finished";

    DisplayTestRun(Kind kind, Driver driver, long now) {
        this.kind=kind;this.driver=driver;deadline=now+START_MS;
    }
    boolean busy() { return phase!=Phase.DONE; }
    long remaining(long now) { return Math.max(0,(deadline-now+999)/1000); }
    void ready(boolean ok, String detail, long now) {
        if(phase!=Phase.STARTING)return;
        if(!ok){restore(detail,now);return;}
        if(kind==Kind.RESTORE){restore("2D recovery requested",now);return;}
        phase=Phase.MONO;deadline=now+MODE_MS;stableSince=-1;accepted=false;
        driver.status("Requesting 2D at 1920 × 1080 / 60 Hz");driver.mode(false,++request);
    }
    void reply(int id, boolean ok, String detail, long now) {
        if(id!=request||phase==Phase.DONE)return;
        if(phase==Phase.RESTORING){restoreAccepted=ok;return;}
        if(phase!=Phase.MONO&&phase!=Phase.STEREO)return;
        if(!ok){restore(detail,now);return;}
        accepted=true;
    }
    void capturedBefore(long now) {
        if(phase!=Phase.BASELINE)return;
        phase=Phase.STEREO;deadline=now+MODE_MS;accepted=false;
        driver.status("Requesting SBS at 3840 × 1080 / 60 Hz");driver.mode(true,++request);
    }
    void sample(long now, boolean attached, int display, int width, int height, float hz, boolean on) {
        if(phase==Phase.DONE)return;
        if(phase==Phase.STARTING){
            if(!attached)restore("Glasses disconnected or changed during control startup",now);
            else if(now>=deadline)restore("Glasses control startup timed out",now);
            return;
        }
        if(!attached&&phase!=Phase.RESTORING){restore("Glasses disconnected or changed",now);return;}
        if(phase==Phase.BASELINE){if(now>=deadline)restore("The 2D snapshot did not finish in time",now);return;}
        boolean stereo=phase==Phase.STEREO||(phase==Phase.PICTURE&&kind==Kind.SBS);
        boolean matching=attached&&on&&display>0&&width==(stereo?3840:1920)&&height==1080
                &&(phase==Phase.RESTORING?hz>=30&&hz<=240:Math.abs(hz-60)<.5f);
        if(phase==Phase.STEREO&&display>0&&height>0&&height!=1080&&!stereoCaptured){
            stereoCaptured=true;driver.capture("stereo");
        }
        if(phase==Phase.PICTURE){
            if(!matching){restore("The display changed or lost its test resolution",now);return;}
            if(now>=deadline)restore(kind==Kind.SBS?"SBS output reached 3840 × 1080 / 60 Hz":"2D output reached 1920 × 1080 / 60 Hz",now);
            return;
        }
        if(matching&&(phase==Phase.RESTORING?restoreAccepted:accepted)) {
            if(stableSince<0||stableDisplay!=display){stableSince=now;stableDisplay=display;}
            if(now-stableSince>=1000){
                stableSince=-1;
                if(phase==Phase.RESTORING){driver.capture("restored");finish(outcome+". 2D output detected.",true);return;}
                if(phase==Phase.MONO){
                    if(kind==Kind.SBS){
                        phase=Phase.BASELINE;deadline=now+32_000;
                        driver.status("Saving the 2D baseline before switching to SBS…");driver.capture("before");return;
                    }
                    driver.capture("before");
                } else if(!stereoCaptured){stereoCaptured=true;driver.capture("stereo");}
                phase=Phase.PICTURE;deadline=now+PICTURE_MS;
                driver.status("Look for the test pattern in the glasses");driver.pattern(kind==Kind.SBS);return;
            }
        } else {stableSince=-1;stableDisplay=-1;}
        if(now>=deadline){
            if(phase==Phase.RESTORING){driver.capture("restored");finish(outcome+". 2D recovery was not confirmed. Unplug and reconnect the glasses.",false);}
            else {
                if(phase==Phase.STEREO&&!stereoCaptured){stereoCaptured=true;driver.capture("stereo");}
                restore((phase==Phase.STEREO?"SBS":"2D")+" output did not reach its test timing (observed "+width+" × "+height+" / "
                        +String.format(java.util.Locale.ROOT,"%.1f",hz)+" Hz)",now);
            }
        }
    }
    void stop(long now) { if(phase!=Phase.DONE&&phase!=Phase.RESTORING)restore("Test stopped",now); }
    void fail(String message,long now) { restore(message,now); }
    private void restore(String result, long now) {
        if(phase==Phase.RESTORING||phase==Phase.DONE)return;
        outcome=result;phase=Phase.RESTORING;deadline=now+RESTORE_MS;stableSince=-1;restoreAccepted=false;
        driver.status("Returning the glasses to 2D…");driver.restore(++request);
    }
    private void finish(String message, boolean restored) {phase=Phase.DONE;driver.complete(message,restored);}
}
