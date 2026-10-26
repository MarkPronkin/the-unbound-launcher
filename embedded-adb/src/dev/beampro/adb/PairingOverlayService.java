package dev.beampro.adb;

import android.app.*;
import android.content.*;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import dev.beampro.settings.Signature;

/** A user-opened, short-lived panel so the system pairing dialog never has to lose focus. */
public final class PairingOverlayService extends Service {
    private WindowManager windows;
    private LinearLayout panel;
    private TextView status;
    private EditText code, port;
    private Button pair;
    private AdbDiscovery discovery;
    private boolean notificationMode, pairingAccepted, completed, destroyed;
    private String lastNotice, replyError;
    private static final String CHANNEL = "unbound-adb-pairing";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable update = new Runnable() {
        public void run() {
            if (panel != null) {
                status.setText(AdbBootstrap.status(PairingOverlayService.this));
                pair.setEnabled(!AdbBootstrap.busy());
            }
            refreshNotification();
            handler.postDelayed(this, 700);
        }
    };
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "On-device ADB pairing", NotificationManager.IMPORTANCE_HIGH));
        manager.cancel(7319); // Remove completion notices from earlier versions.
        startForeground(7318, notification("Keep Android's code dialog open. Tap Enter pairing code below."));
        discovery = new AdbDiscovery(getApplicationContext());
        handler.post(update); handler.postDelayed(this::stopSelf, 300000);
    }
    private Notification notification(String message) {
        boolean busy = AdbBootstrap.busy();
        String title = pairingAccepted ? "Unbound · Code accepted" : busy ? "Unbound · Checking pairing code" : "Unbound · Pair this phone";
        PendingIntent closeAction = PendingIntent.getService(this, 1, new Intent(this, PairingOverlayService.class).setAction("close"), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title).setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                .setOngoing(true).setOnlyAlertOnce(true);
        if (!busy && !pairingAccepted) {
            PendingIntent reply = PendingIntent.getService(this, 2, new Intent(this, PairingOverlayService.class).setAction("pair-reply"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            RemoteInput input = new RemoteInput.Builder("pairing-code").setLabel("Six-digit code (or code:pairing-port)").build();
            builder.addAction(new Notification.Action.Builder(null, "Enter pairing code", reply).addRemoteInput(input).setAllowGeneratedReplies(false).build());
        }
        return builder.addAction(new Notification.Action.Builder(null, "Close", closeAction).build()).build();
    }
    private void refreshNotification() {
        if (completed || destroyed) return;
        String current = AdbBootstrap.status(this);
        String message = replyError != null ? replyError : pairingAccepted ? "Code accepted. Starting AR app windows…"
                : AdbBootstrap.busy() || current.contains("failed") || current.startsWith("Enter ") ? current
                : "Keep Android's code dialog open. Tap Enter pairing code below.";
        if (!message.equals(lastNotice)) publish(message);
    }
    private void publish(String message) {
        getSystemService(NotificationManager.class).notify(7318, notification(message));
        lastNotice = message;
    }
    private void codeAccepted() {
        if (destroyed) return;
        pairingAccepted = true;
        publish("Code accepted. Starting AR app windows…");
    }
    private void pairingComplete() {
        if (destroyed) return;
        completed = true;
        handler.removeCallbacksAndMessages(null);
        // Replace the reply notification itself, so Android clears its inline-reply state.
        stopForeground(STOP_FOREGROUND_DETACH);
        getSystemService(NotificationManager.class).notify(7318, completionNotification());
        stopSelf();
    }
    private Notification completionNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 3, new Intent(this, AdbPairingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        String message = AdbBootstrap.ready(this) ? "Ready · Return to Unbound to finish setup."
                : "Code accepted. Open Unbound to connect and start.";
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Unbound · Code accepted")
                .setContentText(message).setContentIntent(open).setOnlyAlertOnce(true)
                .setAutoCancel(true).setTimeoutAfter(30000).build();
    }
    private void showPanel() {
        if (panel != null || !Settings.canDrawOverlays(this)) return;
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        panel = Signature.panel(this); panel.setElevation(dp(12));
        TextView title = Signature.medium(this, "Unbound · Pair this phone", Signature.TITLE, Signature.TEXT); panel.addView(title);
        title.setPadding(0, 0, 0, dp(6));
        TextView instructions = Signature.text(this, "Keep Android's pairing-code dialog open. Enter its code below.", Signature.SUPPORTING, Signature.TEXT_SECONDARY);
        panel.addView(instructions);
        code = AdbPairingActivity.input(this, panel, "Six-digit code", true);
        ((LinearLayout.LayoutParams) code.getLayoutParams()).topMargin = dp(16);
        port = AdbPairingActivity.input(this, panel, "Pairing port (or leave automatic)", false);
        ((LinearLayout.LayoutParams) port.getLayoutParams()).topMargin = dp(8);
        status = Signature.text(this, "Looking for this phone's pairing service…", Signature.CAPTION, Signature.TEXT_SECONDARY);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(12); panel.addView(status, statusParams);
        LinearLayout actions = new LinearLayout(this);
        pair = Signature.button(this, "Pair", Signature.PRIMARY);
        pair.setOnClickListener(v -> {
            int p = AdbPairingActivity.port(port); if (p < 0) return;
            String value = code.getText().toString().trim(); code.setText("");
            AdbBootstrap.pair(this, value, p, 0, discovery, () -> {
                if (AdbBootstrap.paired(this)) {
                    startActivity(new Intent(this, AdbPairingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                    stopSelf();
                }
            });
        });
        Button close = Signature.button(this, "Close", Signature.SECONDARY); close.setOnClickListener(v -> stopSelf());
        actions.addView(close, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams pairParams = new LinearLayout.LayoutParams(0, -2, 1);
        pairParams.setMarginStart(dp(8)); actions.addView(pair, pairParams);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
        actionParams.topMargin = dp(16); panel.addView(actions, actionParams);
        int width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(340));
        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(width, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; layout.y = dp(20);
        layout.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        // Drag the title if the system dialog needs more room.
        title.setOnTouchListener(new View.OnTouchListener() {
            float downY; int originalY;
            public boolean onTouch(View view, android.view.MotionEvent event) {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) { downY = event.getRawY(); originalY = layout.y; return true; }
                if (event.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                    layout.y = Math.max(0, originalY + Math.round(downY - event.getRawY())); windows.updateViewLayout(panel, layout); return true;
                }
                return true;
            }
        });
        try { windows.addView(panel, layout); }
        catch (RuntimeException failure) { android.util.Log.w("UnboundADB", "Pairing overlay unavailable", failure); stopSelf(); }
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "close".equals(intent.getAction())) stopSelf();
        else if (intent != null && "pair-reply".equals(intent.getAction())) {
            notificationMode = true;
            if (AdbBootstrap.busy()) { refreshNotification(); return START_NOT_STICKY; }
            pairingAccepted = false;
            replyError = null;
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            CharSequence raw = results == null ? null : results.getCharSequence("pairing-code");
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("([0-9]{6})(?:[: ]([0-9]{1,5}))?").matcher(raw == null ? "" : raw.toString().trim());
            if (match.matches()) {
                int pairingPort = match.group(2) == null ? 0 : Integer.parseInt(match.group(2));
                AdbBootstrap.pair(this, match.group(1), pairingPort, 0, discovery, this::codeAccepted, this::pairingComplete);
                // Always acknowledge this reply, even when synchronous validation rejects it.
                publish(AdbBootstrap.status(this));
            } else {
                replyError = "Enter six digits. If detection is unavailable, use code:pairing-port.";
                publish(replyError);
            }
        } else if (intent != null && "notification".equals(intent.getAction())) notificationMode = true;
        else if (!notificationMode) showPanel();
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        if (discovery != null) discovery.close();
        if (panel != null) { code.setText(""); try { windows.removeView(panel); } catch (RuntimeException ignored) {} panel = null; }
        if (!completed) stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
    private int dp(int value) { return Signature.dp(this, value); }
}
