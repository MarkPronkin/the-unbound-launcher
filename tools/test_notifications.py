"""Check the default launcher's notification boundary against the pinned SDK."""
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

from build_embedded_adb import BASE
from embedded_adb_manifest import without_notification_listener
from notifications import compile_notification_adapters, without_notification_mirroring
from verify_embedded_adb import classes, elements, verify_attribute_order

JAVA = Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '17'], text=True).strip())


class NotificationTests(unittest.TestCase):
    def test_only_listener_service_and_its_filter_are_removed_from_manifest(self):
        with zipfile.ZipFile(BASE) as archive:
            original = archive.read('AndroidManifest.xml')
        before = elements(original)
        i = next(i for i, (tag, attrs) in enumerate(before)
                 if tag == 'service' and attrs.get('name') == 'com.xreal.evapro.NotifyService')
        self.assertEqual(before[i][1]['permission'], 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE')
        self.assertEqual(before[i+1:i+3], [('intent-filter', {}), ('action', {'name': 'android.service.notification.NotificationListenerService'})])
        patched = without_notification_listener(original)
        self.assertEqual(elements(patched), before[:i] + before[i+3:])
        self.assertIn(('uses-permission', {'name': 'android.permission.POST_NOTIFICATIONS'}), elements(patched))
        verify_attribute_order(patched)

    def test_renderer_adapters_remain_and_notification_reader_classes_are_removed(self):
        with tempfile.TemporaryDirectory() as temp, zipfile.ZipFile(BASE) as archive:
            work = Path(temp)
            adapters = compile_notification_adapters(work / 'adapters', JAVA, Path.home() / 'Library/Android/sdk')
            for name in ('classes.dex', 'classes2.dex'):
                with self.subTest(name=name):
                    original = archive.read(name)
                    result = without_notification_mirroring(name, original, adapters, work / name, JAVA)
                    self.assertNotIn(b'Landroid/service/notification/NotificationListenerService;', result)
                    self.assertNotIn(b'android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS', result)
                    expected = set(classes(original))
                    if name == 'classes2.dex':
                        remove = {cls for cls in expected if cls.startswith(('Lcom/xreal/evapro/NotifyService', 'Lcom/xreal/evapro/NotifyCallback', 'Lcom/xreal/evapro/NotifyHelper$'))}
                        self.assertEqual(len(remove), 13)
                        expected -= remove
                    self.assertEqual(set(classes(result)), expected)


if __name__ == '__main__':
    unittest.main()
