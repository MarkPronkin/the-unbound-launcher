"""Test automatic XREAL routing, Pixel cap handling and watchdog recovery offline."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_display_startup import ROOT, JAVA


class ManagedDisplayTests(unittest.TestCase):
    def test_hotplug_without_display_callback_delivery(self):
        with tempfile.TemporaryDirectory() as work:
            subprocess.run([str(JAVA/'bin/javac'),'-d',work,
                            str(ROOT/'bridge/src/dev/beampro/bridge/ManagedDisplayPolicy.java'),
                            str(ROOT/'bridge/src/dev/beampro/bridge/DisplaySnapshotReader.java'),
                            str(ROOT/'tools/DisplaySnapshotReaderTest.java')],check=True)
            result=subprocess.run([str(JAVA/'bin/java'),'-cp',work,'DisplaySnapshotReaderTest'],
                                  text=True,capture_output=True,check=True)
            self.assertIn('PASS direct display snapshot regressions: 15',result.stdout)
        capture=(ROOT/'artifacts/pixel-display-015-user-test-20261026/display.txt').read_text()
        self.assertRegex(capture,r'mPid=6044 mUid=2000[^\n]*cached=true[^\n]*pendingDisplayEvents=6')

    def test_policy_and_watchdog_with_real_pixel_cap_fixture(self):
        with tempfile.TemporaryDirectory() as work:
            subprocess.run([str(JAVA/'bin/javac'),'-d',work,
                            str(ROOT/'bridge/src/dev/beampro/bridge/ManagedDisplayPolicy.java'),
                            str(ROOT/'tools/ManagedDisplayPolicyTest.java')],check=True)
            result=subprocess.run([str(JAVA/'bin/java'),'-cp',work,'ManagedDisplayPolicyTest',
                                   str(ROOT/'artifacts/pixel-display-014-live-20261026/display.txt')],
                                  text=True,capture_output=True,check=True)
            self.assertIn('PASS 45 managed-display assertions',result.stdout)

    def test_helper_uses_non_persistent_mode_api_and_exact_pixel_preference_api(self):
        # These are API compatibility checks against the framework copied read-only
        # from the actual Pixel, rather than guessed Binder transaction numbers.
        source=(ROOT/'bridge/src/dev/beampro/bridge/ManagedDisplay.java').read_text()
        api=(ROOT/'artifacts/pixel-display-014-live-20261026/framework-source/android/hardware/display/IDisplayManager.java').read_text()
        for name in ('getConnectionPreference','setConnectionPreference','setUserPreferredDisplayMode',
                     'shouldAlwaysRespectAppRequestedMode','setShouldAlwaysRespectAppRequestedMode','enableConnectedDisplay'):
            self.assertIn(name,api)
            self.assertIn('"'+name+'"',source)
        self.assertIn('screen.id,target,false',source)
        self.assertNotIn('setGlobalUserPreferredDisplayMode',source)
        reader=(ROOT/'bridge/src/dev/beampro/bridge/DisplaySnapshotReader.java').read_text()
        for name in ('getDisplayIds','getDisplayInfo'):
            self.assertIn(name,api)
            self.assertIn('"'+name+'"',reader)
        self.assertIn('MRG',reader)


if __name__=='__main__':unittest.main()
