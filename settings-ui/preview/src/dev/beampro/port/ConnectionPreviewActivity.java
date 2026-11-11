package dev.beampro.port;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;

/** Isolated visual harness; no USB, SDK, helper or production state is touched. */
public final class ConnectionPreviewActivity extends Activity {
    ConnectionAnimation animation;
    FirmwareWarningView firmware;
    int overrides, exits;
    int retries;
    private final Handler main = new Handler();
    @Override public void onCreate(Bundle state) { super.onCreate(state); start(); }
    private void start() {
        if (animation != null) animation.dispose();
        if (getIntent().getBooleanExtra("firmware",false)) {
            boolean pending=getIntent().getBooleanExtra("firmware_unverified",false);
            FirmwarePolicy.Result result=pending?FirmwarePolicy.unavailable(6,"The Software firmware read is taking too long. Compatibility has not been verified. Wait for the read to finish or exit and reconnect the glasses.")
                    :new FirmwarePolicy.Result(FirmwarePolicy.Status.MISMATCH,"XREAL One Pro","2.0.0 · beta","1.9.1 · stable",
                    "Software firmware does not match the recommended stable release.");
            firmware=new FirmwareWarningView(this,result,
                    ()->{exits++;finish();},()->{overrides++;getIntent().putExtra("firmware",false);start();});
            firmware.update(result,pending);
            firmware.show(this);return;
        }
        animation = new ConnectionAnimation(this, () -> { retries++; start(); }, this::finish);
        animation.show(this);
        if (getIntent().getBooleanExtra("failure", false)) animation.failure("The glasses disconnected or could not start. Reconnect them, then try again.", false);
        else if (getIntent().getBooleanExtra("connected", false)) animation.connected();
        else if (!getIntent().getBooleanExtra("hold", false)) {
            main.postDelayed(() -> animation.stage("Preparing glasses"), 1800);
            main.postDelayed(() -> animation.stage("Starting AR Space"), 3800);
            main.postDelayed(() -> animation.connected(), 6500);
        }
    }
    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); if(animation!=null)animation.dispose(); super.onDestroy(); }
}
