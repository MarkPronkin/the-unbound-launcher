package dev.beampro.port;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/** Sender contracts for the bundled SDK's legacy two-argument receiver registrations. */
public final class ReceiverRegistration {
    private ReceiverRegistration() {}

    public static Intent internal(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) {
            return context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        }
        return context.registerReceiver(receiver, filter);
    }

    public static Intent samsungBadge(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        return fromSender(context, receiver, filter, "com.sec.android.provider.badge.permission.WRITE");
    }

    public static Intent smsRetriever(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        return fromSender(context, receiver, filter, "com.google.android.gms.auth.api.phone.permission.SEND");
    }

    public static Intent downloadComplete(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        return fromSender(context, receiver, filter, "android.permission.SEND_DOWNLOAD_COMPLETED_INTENTS");
    }

    private static Intent fromSender(Context context, BroadcastReceiver receiver, IntentFilter filter, String permission) {
        if (Build.VERSION.SDK_INT >= 33) {
            return context.registerReceiver(receiver, filter, permission, null, Context.RECEIVER_EXPORTED);
        }
        return context.registerReceiver(receiver, filter, permission, null);
    }
}
