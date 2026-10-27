"""Check updater registration against the real binary manifest and semantic versions on the JVM."""
import subprocess
import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from updater_manifest import INSTALLER, PROVIDER, patch_updater_manifest
from verify_embedded_adb import elements, verify_attribute_order
from build_embedded_adb import BASE
from test_display_startup import JAVA, ROOT


class AppUpdaterTests(unittest.TestCase):
    def test_private_registration_preserves_existing_components_and_attribute_order(self):
        with ZipFile(BASE) as archive:
            original = archive.read("AndroidManifest.xml")
        patched = patch_updater_manifest(original)
        before, after = elements(original), elements(patched)
        self.assertEqual(len(after), len(before) + 2)
        self.assertEqual([element for element in after if element[1].get("name") not in {INSTALLER, PROVIDER}], before)
        installer = next(attrs for tag, attrs in after if attrs.get("name") == INSTALLER)
        provider = next(attrs for tag, attrs in after if attrs.get("name") == PROVIDER)
        self.assertEqual(installer["exported"], 0)
        self.assertEqual(provider["exported"], 0)
        self.assertEqual(provider["grantUriPermissions"], 0xFFFFFFFF)
        self.assertEqual(provider["authorities"], "com.xreal.evapro.nebula.unbound.updates")
        self.assertNotIn("process", provider)
        verify_attribute_order(patched)
        self.assertEqual(patch_updater_manifest(patched), patched)

    def test_release_ordering_numeric_components_prereleases_and_display_names(self):
        source = r'''package dev.beampro.settings;
public final class VersionChecks {
 public static void main(String[] args) {
  String[] order={"0.1.0-alpha", "0.1.0-alpha.2", "0.1.0-alpha.10", "0.1.0-beta", "0.1.0-rc.1", "0.1.0", "0.1.1", "0.2.0", "0.10.0", "1.0.0", "100000000000000000000.0.0"};
  for(int i=0;i<order.length;i++) for(int j=0;j<order.length;j++)
   if(Integer.signum(ReleaseVersion.parse(order[i]).compareTo(ReleaseVersion.parse(order[j])))!=Integer.signum(i-j)) throw new AssertionError(order[i]+" vs "+order[j]);
  if(ReleaseVersion.parse("0.1.0 alpha").compareTo(ReleaseVersion.parse("v0.1.0-alpha+build.3"))!=0)throw new AssertionError("display spelling");
  for(String bad:new String[]{"", "latest", "1.0", "v1.0.0-", "v1.0.0-alpha..1", "../1.0.0", "1.0.0 alpha!"})
   if(ReleaseVersion.parse(bad)!=null)throw new AssertionError("accepted "+bad);
  System.out.println("PASS release version ordering");
 }
}'''
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            test = work / "VersionChecks.java"
            test.write_text(source)
            subprocess.run([str(JAVA / "bin/javac"), "--release", "8", "-d", tmp,
                str(ROOT / "settings-ui/src/dev/beampro/settings/ReleaseVersion.java"), str(test)], check=True)
            result = subprocess.run([str(JAVA / "bin/java"), "-cp", tmp, "dev.beampro.settings.VersionChecks"], capture_output=True, text=True, check=True)
            self.assertIn("PASS", result.stdout)


if __name__ == "__main__":
    unittest.main()
