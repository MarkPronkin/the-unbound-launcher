package dev.beampro.adb;

/** Stand-in for the original launcher's deferred initialization in the isolated APK. */
public final class SetupApplication extends android.app.Application {
    private void mainProcessInit() {}
}
