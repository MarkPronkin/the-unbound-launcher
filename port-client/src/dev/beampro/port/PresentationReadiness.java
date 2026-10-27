package dev.beampro.port;

/** Native XREAL output, judged from the physical mode alone.
 *  The renderer runs in the :space process, where the SDK service is never started, so its
 *  mode namespace and the main process's (possibly filtered) target are unavailable there.
 *  The glasses advertise only their current EDID mode plus VGA fallbacks, so any native
 *  1080p mode means the requested mode switch has completed. */
public final class PresentationReadiness {
    private PresentationReadiness() {}
    public static boolean nativeOutput(int width,int height,float hz) {
        if((width!=1920&&width!=3840)||height!=1080)return false;
        for(float rate:new float[]{60,72,90,120})if(Math.abs(hz-rate)<0.5f)return true;
        return false;
    }
}
