package com.xreal.glassesdisplayplugevent;

/** Emulator-only implementation of the original SDK's model lookup. */
public final class GlassesInitSetting {
    private static final GlassesInitSetting INSTANCE = new GlassesInitSetting();
    public static int previewType = 7;
    public static boolean previewSleepLevels = true, previewSleepUnsupported;
    public static GlassesInitSetting getInstance() { return INSTANCE; }
    public int checkGlassesType() { return previewType; }
    public boolean isGSeriesGlasses() { return previewSleepLevels; }
    public boolean isHSeriesGlasses10_11() { return previewSleepUnsupported; }
}
