"""Keep machine metadata, caches and private files out of the APK's source bundle."""
from pathlib import Path
import tempfile
import unittest
import zipfile

from build_embedded_adb import ROOT, SOURCE_SECRET, pairing_source_file, write_pairing_sources


class PairingSourceArchiveTests(unittest.TestCase):
    def test_source_and_license_allowlist(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ["embedded-adb/Main.java", "embedded-adb/native.c", "embedded-adb/CMakeLists.txt",
                         "embedded-adb/Makefile", "embedded-adb/LICENSE", "embedded-adb/LICENSES/Apache-2.0"]:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("source or license")
                self.assertTrue(pairing_source_file(path, root), name)

    def test_metadata_caches_and_private_paths_excluded(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in ["embedded-adb/.DS_Store", "embedded-adb/.hidden/Main.java", "embedded-adb/._Main.java",
                         "embedded-adb/__MACOSX/Main.java", "embedded-adb/__pycache__/Main.pyc",
                         "embedded-adb/build/Generated.java", "embedded-adb/cache/metadata.json",
                         "embedded-adb/node_modules/metadata.json", "embedded-adb/keys/signing.json",
                         "embedded-adb/signing.pem", "embedded-adb/signing.pem.txt", "embedded-adb/signing.p12",
                         "embedded-adb/id_rsa", "embedded-adb/credentials.json", "embedded-adb/passwords.txt",
                         "embedded-adb/vendor/deps/library.jar", "embedded-adb/image.png"]:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("excluded")
                self.assertFalse(pairing_source_file(path, root), name)

    def test_symlinks_excluded(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "Main.java"
            source.write_text("source")
            link = root / "Linked.java"
            link.symlink_to(source)
            self.assertFalse(pairing_source_file(link, root))
            directory = root / "linked-directory"
            directory.symlink_to(root, target_is_directory=True)
            self.assertFalse(pairing_source_file(directory / "Main.java", root))

    def test_secret_markers_rejected(self):
        for marker in [b"-----BEGIN " + b"PRIVATE KEY-----", b"-----BEGIN RSA " + b"PRIVATE KEY-----",
                       b"ghp_" + b"a" * 36, b"github_pat_" + b"a" * 50]:
            self.assertIsNotNone(SOURCE_SECRET.search(marker))
        self.assertIsNone(SOURCE_SECRET.search(b"source code without credentials"))

    def test_real_archive_has_only_safe_paths_and_fixed_metadata(self):
        with tempfile.TemporaryDirectory() as temporary:
            first, second = Path(temporary) / "first.zip", Path(temporary) / "second.zip"
            write_pairing_sources(first)
            write_pairing_sources(second)
            self.assertEqual(first.read_bytes(), second.read_bytes())
            with zipfile.ZipFile(first) as archive:
                self.assertIn("LICENSE", archive.namelist())
                self.assertIn("version.json", archive.namelist())
                self.assertIn("tools/build_embedded_adb.py", archive.namelist())
                self.assertIn("embedded-adb/vendor/libadb/LICENSES/GPL-3.0-or-later", archive.namelist())
                self.assertFalse(archive.comment)
                for info in archive.infolist():
                    self.assertTrue(pairing_source_file(ROOT / info.filename))
                    self.assertEqual(info.date_time, (1980, 1, 1, 0, 0, 0))
                    self.assertEqual(info.extra, b"")
                    self.assertEqual(info.comment, b"")
                    self.assertIsNone(SOURCE_SECRET.search(archive.read(info)))


if __name__ == "__main__":
    unittest.main()
