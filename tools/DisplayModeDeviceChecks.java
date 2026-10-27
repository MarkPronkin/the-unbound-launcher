package dev.beampro.modechecks;

import android.app.Instrumentation;
import android.os.Bundle;
import android.view.Display;

/** Load the full SDK library and execute its actual mode-binding constructor. */
public final class DisplayModeDeviceChecks extends Instrumentation {
    String variant;
    @Override public void onCreate(Bundle args) {super.onCreate(args);variant=args.getString("variant");start();}
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            boolean missing=false;
            try {Display.Mode.class.getMethod("getSupportedHdrTypes");}
            catch(NoSuchMethodException expected){missing=true;}
            if(!missing)throw new AssertionError("Use Android 13 or older to reproduce this API failure");
            android.util.Log.i("ModeBindingCheck","Verified missing Mode.getSupportedHdrTypes; variant="+variant);
            System.loadLibrary("mode_"+variant);
            if(com.xreal.sdk.display.GlassesDisplay.nativeRunOnGlassesDisplay()!=1)
                throw new AssertionError("Required native mode IDs missing or unused HDR slot not null");
            result.putString("stream","PASS full native Display.Mode initializer with real Android JNI; missing API retained\n");
            finish(-1,result);
        } catch(Throwable error) {
            result.putString("stream",android.util.Log.getStackTraceString(error));finish(0,result);
        }
    }
}
