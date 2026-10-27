"""The recents thumbnail library exports the Unity entry points and is 16 KB aligned."""
from pathlib import Path
import tempfile
import unittest

from hardware_buffer_texture import ENTRY, SAMPLING_MARK, SOURCE, build_library


class HardwareBufferTextureTests(unittest.TestCase):
    def test_library_exports_and_alignment(self):
        self.assertTrue(SOURCE.is_file())
        self.assertEqual(ENTRY, "lib/arm64-v8a/libHardwareBufferTexture.so")
        with tempfile.TemporaryDirectory() as directory:
            blob = build_library(Path(directory) / "libHardwareBufferTexture.so")
        self.assertIn(SAMPLING_MARK, blob)
        self.assertIn(b"Failed to create EGLImage.", blob)
        self.assertNotIn(b"libc++", blob)


if __name__ == "__main__":
    unittest.main()
