# XREAL USB permission on the glasses' desktop

The user reported a first-install USB prompt on the Pixel's glasses desktop.
Live inspection of installed build 2891 identifies **UsbConfirmActivity**, with
text **Open The Unbound Launcher to handle XREAL One?**, on external display 3.
This is Android's automatic USB handler confirmation, distinct from a request
made by the glasses SDK. The initial SDK-only explanation below was incomplete.

## Corrected launch build 2892

The manifest no longer advertises SplashActivity as a USB device handler. Only
that activity's USB_DEVICE_ATTACHED intent filter and matching metadata are
removed. The normal MAIN/LAUNCHER filter and USB attach/detach broadcast receiver
remain. Open Unbound on the phone; it continues to detect connected glasses.
Android has no Unbound USB handler activity to offer in the “Open…” confirmation.

The live trace also showed the helper failing while decoding a UsbDevice from
its service Bundle. The exact shipped helper reproduces IllegalArgumentException
after createShellContext; the same API works before that system Context is
created. The copied Pixel UsbDevice constructor checks ActivityThread.isSystem()
and requires its serial reader to be a local IUsbSerialReader.Stub. The shell
receives a Proxy, so the system-Context process cannot decode it.

A bounded separate shell worker now performs USB enumeration and granting
without initializing ActivityThread or a Context. It verifies the current
installed launcher UID and the exact live XREAL device again. Older and newer
grant signatures remain supported; grants stay temporary. The parent keeps the
same Binder caller and installation checks and never decodes a USB parcel.
The actual corrected helper passes the same on-Pixel probe that fails for 2891.

A full DEX inventory also found four additional requests in the lower-level
controller, its retry timer and the dormant OTA wrapper. All five original SDK
calls now use the deferred client. Distinct PendingIntents for one device keep
separate results; repeated requests for the same device/callback still coalesce.
The production APK audit rejects any remaining original unhooked call.

Thirty-one focused tests pass, including 52 client/helper assertions, both DEX
restoration paths, all-call coverage and the manifest regression. The full APK,
manifest, signing/alignment and display-defaults audits pass. The exact helper
component is validated on the physical Pixel 8 Pro. The complete 2892 APK has
not been installed on that phone; fresh-install/reconnect validation is pending.
The existing stuck dialog was cleared with Only this time, leaving the default
handler preference unchanged. Raw screenshots and logs remain private under
artifacts/usb-permission-followup/.

## Initial SDK-only change: build 2891, superseded

The original `AirGlasses` polling path calls `AbstractGlasses.requestPermission`,
then `UsbPermissionHelper.requestPermission(UsbDevice, OnGrantedResult)`. The
helper registers the original SDK callback, constructs its broadcast
`PendingIntent`, and calls `UsbManager.requestPermission`. Android can launch its
permission activity on an external desktop. The SDK receiver expects the
`device` and `permission` extras and dispatches its callback on the main thread.

The embedded build now replaces that one framework call with
`UsbPermissionCompat.requestPermission`, passing the existing Context, manager,
device and PendingIntent. The original map, receiver, callback and all other
instructions remain intact; restoration audits every class against the baseline.

For XREAL vendor ID **13080**, the replacement waits asynchronously for the
on-device ADB helper. Fresh installs therefore do not open a USB dialog before
pairing is complete. Once ready, a dedicated Binder operation asks the helper
to grant access to the actual connected USB device. The helper re-enumerates the
live devices, checks the requested identity, and grants only to the current
installed Unbound UID. Other signed clients, shell callers, unrelated vendors,
stale devices and stale installation UIDs are rejected. The operation is only
enabled in the production embedded helper.

The copied Pixel Android 17 `IUsbManager` exposes
`grantDevicePermission(UsbDevice, String packageName, int uid, boolean isPersistent)`.
Older Android uses `grantDevicePermission(UsbDevice, int uid)`. The adapter probes
these exact signatures, uses `isPersistent=false` on the newer contract, and
never retries a rejected grant through another signature. No default USB handler
or permanent device permission is configured.

The app checks `UsbManager.hasPermission` after the grant before returning a
successful result through the SDK's existing PendingIntent. Android's camera and
microphone privacy checks still apply. The XREAL path never calls
`requestPermission` a second time: this avoids reopening the dialog if a detach
or permission revocation races the callback. A disconnect returns failure and
cancels the pending request. Repeated SDK requests for one connection coalesce.
Other USB vendors retain their original Android permission flow.

## Earlier host validation: build 2891

Twenty-three focused host tests pass, with 44 client/helper assertions. They
execute both production adapters, including first-install
deferral, an already-authorized connection, duplicate requests, detach,
replacement hardware, a restarting helper, an older helper, a rejected grant,
privacy-blocked readback and unrelated devices. They verify both framework grant
contracts, package/UID restrictions and the nonpersistent flag. The copied Pixel
framework contract is also checked directly. The DEX patch is reversible and
rejects double application.

The production APK audit checks the actual helper handler and deferred client
calls, both permission readbacks, the original PendingIntent delivery and the
absence of another framework dialog request in the deferred path.

The corrected build retains the public name **0.1.0 alpha**, increasing Android's
version code to **2891**. The earlier 2890 launch package is retained locally.
No physical phone was attached during this change. Fresh-install behavior on
Pixel with XREAL One still needs wearer confirmation. An already-open dialog
from the old build may require unplugging the glasses; install the update,
start the new on-device helper, then reconnect. On a fresh installation, finish
the normal pairing setup first.

## Framework references

- Android's permission activity grants device access and returns a PendingIntent
  response: [AOSP UsbPermissionActivity](https://android.googlesource.com/platform/frameworks/base/+/master/packages/SystemUI/src/com/android/systemui/usb/UsbPermissionActivity.java).
- The USB service protects direct grants with `MANAGE_USB`:
  [AOSP UsbService](https://android.googlesource.com/platform/frameworks/base/+/master/services/usb/java/com/android/server/usb/UsbService.java).
- Android's shell package requests that permission:
  [AOSP Shell manifest](https://android.googlesource.com/platform/frameworks/base/+/master/packages/Shell/AndroidManifest.xml).
- Existing authorization, capture privacy checks and PendingIntent result extras:
  [AOSP UsbUserPermissionManager](https://android.googlesource.com/platform/frameworks/base/+/master/services/usb/java/com/android/server/usb/UsbUserPermissionManager.java).

The Pixel contract above comes from this workspace's copied device framework,
not an assumption that its API still matches AOSP master. Raw extracts and test
logs are kept locally under `artifacts/usb-permission/`.
