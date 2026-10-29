# Launcher notification delivery

On the Pixel 8 Pro / Android 17, build 2938 received phone notifications in
`NotifyService`, with notification access granted and AR notifications enabled.
A test without a tap action reached Unity's `SystemNotificationView.Show`.
A real app notification with an activity `PendingIntent` failed before enqueue:

```
AndroidJavaException: java.lang.NullPointerException
  com.xreal.evapro.ActivityManagerProxy.getTarget
  com.xreal.evapro.NotifyHelper.getIntent
```

The original system app inspected the underlying intent through restricted
Android APIs. Those reflection methods are unavailable to the port's ordinary
app identity. The callback reached Unity, but converting its tap action threw
and prevented the notification from being displayed.

Build 2940 replaces only `NotifyHelper.getIntent(NotifyInfo)`. It returns null,
selecting the original renderer's supported `Event_Native_App_Open` fallback.
Notification display no longer depends on inspecting the intent. The sender's
`PendingIntent` creator remains available through `getCreatorPackage`. Taps use
the app-level fallback; opening the exact notification conversation or deep link
is not supported by this change. Notification access, callbacks, filtering,
content, icons and Unity's popup timing retain their original behavior.

`tools/notifications.py` and `PatchNotificationsDex.java` apply the edit in the
single-APK builder. The production audit reverses it before checking the other
patches and the original DEX, and verifies that its source is bundled.

Verification and reproduction:

- `tools/test_notifications.py` verifies exact DEX restoration and rejects double
  patching and unrelated DEX changes.
- `tools/verify_notifications_runtime.py` installs an isolated test package with
  the APK's actual notification DEX. It exercises absent, activity, broadcast
  and service tap actions and checks preserved title, package and creator.
- The stock adapter fails on the physical Pixel. The patched adapter passes 16
  checks on the Pixel and Android emulator. Logs are under
  `artifacts/launcher-notifications/`.
- The signed build 2940 is installed on the Pixel. Two real app notification
  posts reach `SystemNotificationView.Show`; both removals reach Unity. The
  notification-action exception is absent. The full single-APK audit passes.
  The receipt is `artifacts/launcher-notifications/pixel-live-verification.json`;
  the final APK is under `artifacts/unbound-launcher-notifications-2940/`.
- The isolated sender can post a real notification into the installed launcher's
  listener with `am instrument -w -e mode post
  dev.beampro.notificationchecks/.NotificationDeviceChecks`. The `cancel` mode
  removes only this test package's notifications. Test notifications and the
  isolated sender have been removed from the Pixel and emulator after testing.

Visual confirmation inside the glasses and notification tap navigation require
a wearer; a renderer `Show` log alone is not proof of their appearance in view.
