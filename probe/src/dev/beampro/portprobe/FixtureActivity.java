package dev.beampro.portprobe;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

/** Own-app content only: never captures another application's screen. */
public class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText("Beam Port Probe\nVirtual display fixture");
        label.setTextSize(28);
        label.setTextColor(0xFFFFFFFF);
        label.setBackgroundColor(0xFF1C7D6E);
        label.setGravity(android.view.Gravity.CENTER);
        setContentView(label);
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 1200);
    }
}
