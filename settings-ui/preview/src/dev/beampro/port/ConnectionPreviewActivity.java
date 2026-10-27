package dev.beampro.port;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;

/** Isolated visual harness; no USB, SDK, helper or production state is touched. */
public final class ConnectionPreviewActivity extends Activity {
    ConnectionAnimation animation;
    int retries;
    private final Handler main = new Handler();
    @Override public void onCreate(Bundle state) { super.onCreate(state); start(); }
    private void start() {
        if (animation != null) animation.dispose();
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
    @Override protected void onDestroy() { main.removeCallbacksAndMessages(null); animation.dispose(); super.onDestroy(); }
}
