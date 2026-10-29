package dev.beampro.controllerpreview;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.widget.TextView;

/** A spatial game fixture that receives globally routed controller buttons. */
public final class GamepadActivity extends Activity {
    static GamepadActivity instance;
    static volatile boolean focused;
    static volatile int releases, motions, focusLosses;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); instance = this;
        TextView game = new TextView(this); game.setText("Spatial gamepad focus fixture"); setContentView(game);
    }
    @Override public void onWindowFocusChanged(boolean value) {
        super.onWindowFocusChanged(value);
        if (focused && !value) focusLosses++;
        focused = value;
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_A) {
            if (event.getAction() == KeyEvent.ACTION_UP) releases++;
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK)) { motions++; return true; }
        return super.dispatchGenericMotionEvent(event);
    }
}
