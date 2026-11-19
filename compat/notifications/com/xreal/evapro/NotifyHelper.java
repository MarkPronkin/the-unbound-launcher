package com.xreal.evapro;

import android.content.Context;
import android.content.Intent;

/** Unity calls these methods even when phone notification mirroring is unavailable. */
public final class NotifyHelper {
    private static final NotifyHelper INSTANCE = new NotifyHelper();
    private NotifyHelper() {}
    public static NotifyHelper getInstance() { return INSTANCE; }
    public void init(Context context) {}
    public void setListener(INotifyListener listener) {}
    public NotifyInfo[] getActiveNotifications() { return new NotifyInfo[0]; }
    public void remove(NotifyInfo info) {}
    public void send(NotifyInfo info) {}
    public Intent getIntent(NotifyInfo info) { return null; }
    public String getCreatorPackage(NotifyInfo info) { return null; }
}
