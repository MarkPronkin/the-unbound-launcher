package dev.beampro.port;

/** Test-only controls for reproducing the old signed APK's native readiness gate. */
public final class FixturePointer {
    static { System.loadLibrary("il2cpp"); }
    public static native void configure(int flags, boolean anchor, boolean advancing);
}
