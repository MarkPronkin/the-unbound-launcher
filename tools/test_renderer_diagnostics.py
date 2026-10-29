"""Opt-in renderer storage, native readback fixtures and Android binary contract."""
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from hardware_buffer_texture import ROOT, ndk_clang
from renderer_diagnostics import build_library


class RendererDiagnosticsTests(unittest.TestCase):
    def test_storage_opt_in_rotation_reset_and_concurrent_export(self):
        java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip()) / "bin"
        with tempfile.TemporaryDirectory() as work:
            subprocess.run([str(java / "javac"), "-d", work,
                            str(ROOT / "settings-ui/src/dev/beampro/settings/RendererTrace.java"),
                            str(ROOT / "tools/RendererTraceTest.java")], check=True)
            result = subprocess.check_output([str(java / "java"), "-cp", work,
                       "dev.beampro.settings.RendererTraceTest", str(Path(work) / "data")], text=True)
            self.assertIn("PASS renderer trace", result)

    def test_native_readback_mono_sbs_multipass_and_unavailable_metadata(self):
        java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip())
        with tempfile.TemporaryDirectory() as work:
            binary = Path(work) / "renderer-readback"
            subprocess.run(["cc", "-std=c11", "-Wall", "-Wextra", "-Werror", "-I" + str(java / "include"),
                            "-I" + str(java / "include/darwin"), str(ROOT / "tools/RendererReadbackTest.c"),
                            "-o", str(binary)], check=True)
            self.assertIn("PASS renderer readback", subprocess.check_output([str(binary)], text=True))

    def test_android_library_has_one_jni_export_and_16kb_alignment(self):
        with tempfile.TemporaryDirectory() as work:
            path = Path(work) / "libUnboundRendererDiagnostics.so"
            build_library(path); tools = ndk_clang().parent
            headers = subprocess.check_output([str(tools / "llvm-readelf"), "-l", str(path)], text=True)
            loads = [line for line in headers.splitlines() if re.search(r"\bLOAD\b", line)]
            self.assertTrue(loads)
            self.assertTrue(all(int(line.split()[-1], 16) >= 16384 for line in loads))
            exports = subprocess.check_output([str(tools / "llvm-nm"), "-D", "--defined-only", str(path)], text=True)
            self.assertEqual([line.split()[-1] for line in exports.splitlines() if " T " in line],
                             ["Java_dev_beampro_settings_RendererDiagnostics_nativeCapture"])


if __name__ == "__main__":
    unittest.main()
