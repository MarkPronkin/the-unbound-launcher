package ai.nreal.nebula.space;

import android.app.*;
import android.os.Bundle;
import android.view.*;
import android.widget.FrameLayout;

public class LaunchSpaceAcrivity extends Activity {
    public volatile int threeMoves, cancels, maxPointers;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout frame = new FrameLayout(this); frame.setId(12345); setContentView(frame);
        show(new ai.nreal.extension.virtualcontroller.TouchPadPortraitFragment());
    }
    public void show(Fragment fragment) { getFragmentManager().beginTransaction().replace(12345,fragment).commit(); }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        maxPointers = Math.max(maxPointers,event.getPointerCount());
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) cancels++;
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && event.getPointerCount() == 3) threeMoves++;
        super.dispatchTouchEvent(event); return true;
    }
}
