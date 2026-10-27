package dev.beampro.port;

import android.animation.ValueAnimator;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Exercise the actual Android view, including recovery controls and animator lifetime. */
public final class ConnectionAnimationChecks extends Instrumentation {
    int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            ConnectionPreviewActivity a = (ConnectionPreviewActivity)startActivitySync(
                    new Intent(getTargetContext(), ConnectionPreviewActivity.class)
                            .putExtra("hold", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            int[] completed = {0}, cancelled = {0};
            waitForIdleSync();
            runOnMainSync(() -> {
                require(a.animation.getWidth() == a.getWindow().getDecorView().getWidth(), "connection fills phone width");
                require(a.animation.getHeight() > a.getWindow().getDecorView().getHeight() * .8f, "connection fills phone height");
                require(find(a.animation,"Connect your\nglasses.") != null, "connection headline");
                a.animation.stage("Waiting for glasses to reconnect");
                require(find(a.animation,"Waiting for glasses to reconnect") != null, "live USB stage");
                a.animation.stage("Preparing glasses");
                require(find(a.animation,"Preparing glasses") != null, "live preparation stage");
                ValueAnimator clock = clock(a.animation);
                require(clock != null, "dot matrix animator exists");
                require(clock.isStarted() == ValueAnimator.areAnimatorsEnabled(), "honor Android animation setting");
                a.animation.setVisibility(View.GONE);
                require(!clock.isStarted(), "stop frames when hidden");
                a.animation.setVisibility(View.VISIBLE);
                a.animation.failure("The glasses disconnected. Reconnect them, then try again.", false);
                require(!clock.isStarted(), "recovery stops animation");
                require(find(a.animation,"Try again").getVisibility() == View.VISIBLE, "retry is reachable after failure");
            });
            waitForIdleSync();
            runOnMainSync(() -> {
                Rect visible = new Rect();
                require(find(a.animation,"Close").getGlobalVisibleRect(visible), "close is on screen");
                find(a.animation,"Try again").performClick();
                require(a.retries == 1, "retry callback invoked");
                a.animation.failure("The previous request is still finishing.", true);
                require(find(a.animation,"Try again").getVisibility() == View.GONE, "unfinished native request cannot retry");
                a.animation.connected(() -> completed[0]++);
                require(find(a.animation,"Trackpad ready.") != null, "trackpad success state");
                ValueAnimator clock = clock(a.animation);
                if (ValueAnimator.areAnimatorsEnabled()) {
                    require(clock.isStarted(), "success runs its finite dot morph");
                    require(completed[0] == 0, "handoff waits for the morph");
                } else {
                    require(!clock.isStarted(), "reduced motion has no success animator");
                    require(completed[0] == 1, "reduced motion completes immediately");
                }
            });
            SystemClock.sleep(ConnectionAnimation.HANDOFF_MS + 350);
            waitForIdleSync();
            runOnMainSync(() -> {
                require(completed[0] == 1, "success handoff completes exactly once");
                require(!clock(a.animation).isStarted(), "final trackpad tableau is static");
                a.animation.connected(() -> completed[0]++);
                require(completed[0] == 1, "duplicate success does not repeat handoff");
                a.animation.dispose();
                require(!clock(a.animation).isStarted(), "dispose releases animator");

                ConnectionAnimation pending = new ConnectionAnimation(a, () -> {}, () -> {});
                a.animation = pending;
                pending.show(a);
                pending.connected(() -> cancelled[0]++);
                pending.dispose();
                require(!clock(pending).isStarted(), "disposal stops a pending morph");
            });
            SystemClock.sleep(ConnectionAnimation.HANDOFF_MS + 150);
            runOnMainSync(() -> {
                require(cancelled[0] == (ValueAnimator.areAnimatorsEnabled() ? 0 : 1),
                        "disposal cancels a pending morph; reduced motion completes immediately");
                a.finish();
            });
            result.putString("stream", "Connection animation runtime checks passed: " + checks + "\n");
            finish(-1, result);
        } catch (Throwable error) {
            result.putString("stream", "Connection animation check failed after " + checks + ": " + error + "\n");
            finish(0, result);
        }
    }
    private ValueAnimator clock(View v) {
        if (v instanceof ConnectionAnimation.DotMatrix) {
            try {
                java.lang.reflect.Field f = v.getClass().getDeclaredField("clock");
                f.setAccessible(true); return (ValueAnimator)f.get(v);
            } catch (Exception e) { throw new AssertionError(e); }
        }
        if (v instanceof ViewGroup) for (int i=0; i<((ViewGroup)v).getChildCount(); i++) {
            ValueAnimator result = clock(((ViewGroup)v).getChildAt(i)); if (result != null) return result;
        }
        return null;
    }
    private TextView find(View v, String text) {
        if (v instanceof TextView && text.contentEquals(((TextView)v).getText())) return (TextView)v;
        if (v instanceof ViewGroup) for (int i=0; i<((ViewGroup)v).getChildCount(); i++) {
            TextView result = find(((ViewGroup)v).getChildAt(i), text); if (result != null) return result;
        }
        return null;
    }
    private void require(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
