import unittest
import zipfile
from launcher_storage import LAYOUT_PATH, verify_launcher_storage
from build_settings_ui import BASELINE


class LauncherStorageTests(unittest.TestCase):
    def test_phone_path_matches_complete_native_consumer_call_chain(self):
        with zipfile.ZipFile(BASELINE) as archive: proof = verify_launcher_storage(archive)
        self.assertEqual(proof['path'], LAYOUT_PATH)
        self.assertEqual(proof['native_chain'][1], 'PathUtility.AppDetailsPath')


if __name__ == '__main__': unittest.main()
