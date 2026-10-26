package dev.beampro.adb;

/** An isolated entry point for exercising the shipping startup guard. */
public final class SetupEntryActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        if (dev.beampro.settings.PermissionSetupActivity.guard(this)) return;
        android.widget.TextView ready = new android.widget.TextView(this);
        ready.setText("Launcher ready"); setContentView(ready);
    }
}
