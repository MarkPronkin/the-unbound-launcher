package dev.beampro.adb;

import android.app.*;
import android.content.*;
import android.os.*;
import android.service.notification.StatusBarNotification;

/** Exercise posted notification content/actions and real reply validation in the isolated APK. */
public final class PairingNotificationChecks extends Instrumentation {
    private PairingOverlayService service;
    private NotificationManager manager;
    private int passed;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            manager = getTargetContext().getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("unbound-adb-pairing", "Pairing test", NotificationManager.IMPORTANCE_HIGH));
            runOnMainSync(() -> {
                try {
                    service = new PairingOverlayService();
                    java.lang.reflect.Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
                    attach.setAccessible(true); attach.invoke(service, getTargetContext());
                } catch (Exception error) { throw new AssertionError(error); }
                Notification initial = (Notification) invoke("notification", new Class<?>[]{String.class}, "Enter pairing code");
                require(reply(initial), "initial notice offers code entry");
                require((initial.flags & Notification.FLAG_ONGOING_EVENT) != 0, "code entry remains available during pairing");
                submit("bad-code");
            });
            Notification invalid = posted("Enter six digits");
            require(content(invalid).contains("Enter six digits"), "invalid reply displays validation immediately");
            require(reply(invalid), "invalid reply offers another attempt");
            runOnMainSync(() -> invoke("refreshNotification", new Class<?>[0]));
            require(content(posted()).contains("Enter six digits"), "polling preserves validation feedback");
            runOnMainSync(() -> submit("123456:65536"));
            require(content(posted("Enter the pairing port")).contains("Enter the pairing port"), "invalid port immediately replaces reply state");
            runOnMainSync(() -> submit("123456:1"));
            long until = SystemClock.elapsedRealtime() + 10000;
            while (AdbBootstrap.busy() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50);
            runOnMainSync(() -> invoke("refreshNotification", new Class<?>[0]));
            require(content(posted("Pairing failed")).contains("Pairing failed"), "rejected pairing shows failure");
            require(reply(posted()), "failed pairing restores code entry");
            runOnMainSync(() -> invoke("codeAccepted", new Class<?>[0]));
            Notification accepted = posted("Code accepted");
            require(accepted.extras.getString(Notification.EXTRA_TITLE).contains("Code accepted"), "accepted pairing replaces prompt title");
            require(content(accepted).contains("Code accepted"), "acceptance is visible while helper starts");
            require(!reply(accepted), "accepted code removes inline reply action");
            runOnMainSync(() -> invoke("refreshNotification", new Class<?>[0]));
            require(!reply(posted()) && content(posted()).contains("Code accepted"), "polling preserves accepted state");
            runOnMainSync(() -> manager.notify(7318, (Notification) invoke("completionNotification", new Class<?>[0])));
            Notification complete = awaitNotification(n -> (n.flags & Notification.FLAG_ONGOING_EVENT) == 0);
            require(!reply(complete), "completion has no code-entry action");
            require((complete.flags & Notification.FLAG_ONGOING_EVENT) == 0, "completion is dismissible");
            require((complete.flags & Notification.FLAG_AUTO_CANCEL) != 0 && complete.contentIntent != null, "completion opens app and clears on tap");
            require(complete.getTimeoutAfter() == 30000, "completion expires automatically");
            require(manager.getActiveNotifications().length == 1, "completion replaces the same notification");
            until = SystemClock.elapsedRealtime() + 34000;
            while (manager.getActiveNotifications().length > 0 && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
            require(manager.getActiveNotifications().length == 0, "Android removes the completion notice after its timeout");
            result.putString("stream", "Pairing notification checks passed: " + passed + "\n"); finish(-1, result);
        } catch (Throwable error) {
            android.util.Log.e("PairingNotificationChecks", "Notification check failed", error);
            result.putString("stream", "Pairing notification checks failed after " + passed + ": " + error + "\n"); finish(0, result);
        } finally { if (manager != null) manager.cancel(7318); }
    }
    private void submit(String code) {
        Intent intent = new Intent().setAction("pair-reply");
        Bundle reply = new Bundle(); reply.putCharSequence("pairing-code", code);
        RemoteInput.addResultsToIntent(new RemoteInput[]{new RemoteInput.Builder("pairing-code").build()}, intent, reply);
        service.onStartCommand(intent, 0, 1);
    }
    private Object invoke(String method, Class<?>[] types, Object... args) {
        try {
            java.lang.reflect.Method target = PairingOverlayService.class.getDeclaredMethod(method, types);
            target.setAccessible(true); return target.invoke(service, args);
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private Notification posted() {
        return awaitNotification(n -> true);
    }
    private Notification posted(String content) {
        return awaitNotification(n -> content(n).contains(content));
    }
    private Notification awaitNotification(java.util.function.Predicate<Notification> expected) {
        long until = SystemClock.elapsedRealtime() + 3000;
        do {
            for (StatusBarNotification notification : manager.getActiveNotifications())
                if (notification.getId() == 7318 && expected.test(notification.getNotification())) return notification.getNotification();
            SystemClock.sleep(30);
        } while (SystemClock.elapsedRealtime() < until);
        throw new AssertionError("expected pairing notification update was not posted");
    }
    private static String content(Notification notification) { return notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(); }
    private static boolean reply(Notification notification) {
        if (notification.actions != null) for (Notification.Action action : notification.actions)
            if (action.getRemoteInputs() != null && action.getRemoteInputs().length > 0) return true;
        return false;
    }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); passed++; }
}
