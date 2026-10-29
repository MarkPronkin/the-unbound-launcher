"""Exercise real pointer state transitions and Unity-thread session ownership."""
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

from hardware_buffer_texture import ROOT, ndk_clang
from phone_pointer import build_library


class PhonePointerTests(unittest.TestCase):
    def test_snapshot_renewal_partial_failure_rollback_and_restore_retry(self):
        with tempfile.TemporaryDirectory() as work:
            binary = Path(work) / "pointer-state"
            subprocess.run(["cc", "-std=c11", "-Wall", "-Wextra", "-Werror", str(ROOT / "tools/PhonePointerStateTest.c"),
                            "-o", str(binary)], check=True)
            result = subprocess.run([str(binary)], check=True, capture_output=True, text=True)
            self.assertIn("PASS pointer snapshot", result.stdout)

    def test_unity_thread_acknowledgement_ownership_and_delayed_jobs(self):
        java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip()) / "bin"
        with tempfile.TemporaryDirectory() as work:
            subprocess.run([str(java / "javac"), "--release", "8", "-d", work,
                            str(ROOT / "port-client/src/dev/beampro/port/PhonePointerCompat.java"),
                            str(ROOT / "tools/PhonePointerCompatTest.java")], check=True)
            result = subprocess.run([str(java / "java"), "-cp", work, "dev.beampro.port.PhonePointerCompatTest"],
                                    check=True, capture_output=True, text=True, timeout=10)
            self.assertIn("PASS Unity-thread pointer", result.stdout)

    def test_android_library_exports_and_16kb_alignment(self):
        with tempfile.TemporaryDirectory() as work:
            path = Path(work) / "libUnboundPhonePointer.so"
            build_library(path)
            tools = ndk_clang().parent
            headers = subprocess.check_output([str(tools / "llvm-readelf"), "-l", str(path)], text=True)
            loads = [line for line in headers.splitlines() if re.search(r"\bLOAD\b", line)]
            self.assertTrue(loads)
            self.assertTrue(all(int(line.split()[-1], 16) >= 16384 for line in loads))
            exports = subprocess.check_output([str(tools / "llvm-nm"), "-D", "--defined-only", str(path)], text=True)
            self.assertEqual([line.split()[-1] for line in exports.splitlines() if " T " in line],
                             ["Java_dev_beampro_port_PhonePointerCompat_nativeSetSuspended"])


if __name__ == "__main__":
    unittest.main()
