# Launcher notifications

Starting with 0.2.7 alpha build 2975, the default APK omits phone-notification
mirroring and the notification-access prompt. Unbound still posts its own
pairing and AR session notifications. The tester who saw the Play Protect
sensitive-data block with the regular build 2974 confirmed that the test APK
with mirroring removed installed successfully on the same phone.

The APK audit checks for listener services and their implementation.
`python3 tools/verify_notifications_runtime.py --apk PATH --serial EMULATOR`
checks the installed package, the renderer's empty notification list, the setup
options and the pairing notification on an isolated Android emulator.

## Earlier builds with notification mirroring

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

In build 2940, `tools/notifications.py` and `PatchNotificationsDex.java` applied the edit in the
single-APK builder. The production audit reverses it before checking the other
patches and the original DEX, and verifies that its source is bundled.

Build 2940 verification:

- `tools/test_notifications.py` verified exact DEX restoration and rejects double
  patching and unrelated DEX changes.
- `tools/verify_notifications_runtime.py` installed an isolated test package with
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
- The historical isolated sender could post a real notification into the installed launcher's
  listener with `am instrument -w -e mode post
  dev.beampro.notificationchecks/.NotificationDeviceChecks`. The `cancel` mode
  removes only this test package's notifications. Test notifications and the
  isolated sender have been removed from the Pixel and emulator after testing.

Visual confirmation inside the glasses and notification tap navigation require
a wearer; a renderer `Show` log alone is not proof of their appearance in view.
