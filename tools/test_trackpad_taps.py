"""Execute the patched ARM64 tap-release tail and check exact patch composition."""
from pathlib import Path
import struct
import sys
import subprocess
import tempfile
import os
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "artifacts/tools/python"))
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import *
from app_menu import patch_app_menu
from build_settings_ui import BASELINE
from trackpad_taps import (TAP_RELEASE, WRAPPER, QUEUE, DELAYED_QUEUE, TARGETS,
                           RELEASE_DELAY_SECONDS, patch_trackpad_taps, restore_trackpad_taps)


class TrackpadTapTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(BASELINE) as archive:
            cls.original = patch_app_menu(archive.read("lib/arm64-v8a/libil2cpp.so"))
        cls.patched = patch_trackpad_taps(cls.original)

    def test_only_tap_release_and_guarded_wrapper_change(self):
        self.assertEqual(restore_trackpad_taps(self.patched), self.original)
        allowed = {i for target in TARGETS for i in
                   range(target["address"], target["address"] + len(target["replacement"]))}
        changed = {i for i, (before, after) in enumerate(zip(self.original, self.patched)) if before != after}
        self.assertLessEqual(changed, allowed)
        self.assertEqual(self.patched[WRAPPER - 4:WRAPPER], bytes.fromhex("c0035fd6"))
        with self.assertRaises(ValueError): patch_trackpad_taps(self.patched)
        corrupt = bytearray(self.patched); corrupt[-1] ^= 1
        with self.assertRaises(ValueError): restore_trackpad_taps(corrupt)

    def machine(self):
        uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        for address in (TAP_RELEASE & ~4095, WRAPPER & ~4095, DELAYED_QUEUE & ~4095, 0x8000000):
            uc.mem_map(address, 4096)
        uc.mem_write(TAP_RELEASE - 28, self.patched[TAP_RELEASE - 28:TAP_RELEASE + 4])
        uc.mem_write(WRAPPER, self.patched[WRAPPER:WRAPPER + 16])
        stack = 0x8000800
        uc.reg_write(UC_ARM64_REG_SP, stack)
        uc.reg_write(UC_ARM64_REG_X19, 0x11000000)  # Existing main-thread dispatcher.
        uc.reg_write(UC_ARM64_REG_X20, 0x22000000)  # Existing trigger-release delegate.
        uc.mem_write(stack, struct.pack("<6Q", 0x23, 0, 0x21, 0x20, 0x19, 0x12345678))
        return uc, stack

    def test_existing_release_delegate_is_delayed_with_abi_preserved(self):
        uc, stack = self.machine()
        calls = []
        def hook(machine, address, size, user):
            if address != DELAYED_QUEUE: return
            calls.append(address)
            self.assertEqual(machine.reg_read(UC_ARM64_REG_X0), 0x11000000)
            self.assertEqual(machine.reg_read(UC_ARM64_REG_X1), 0x22000000)
            self.assertEqual(machine.reg_read(UC_ARM64_REG_X2), 0)
            self.assertEqual(machine.reg_read(UC_ARM64_REG_X3), 0)
            delay = struct.unpack("<f", struct.pack("<I", machine.reg_read(UC_ARM64_REG_S0)))[0]
            self.assertEqual(delay, RELEASE_DELAY_SECONDS)
            for register, expected in [(UC_ARM64_REG_X19, 0x19), (UC_ARM64_REG_X20, 0x20),
                                       (UC_ARM64_REG_X21, 0x21), (UC_ARM64_REG_X22, 0x23),
                                       (UC_ARM64_REG_LR, 0x12345678)]:
                self.assertEqual(machine.reg_read(register), expected)
            self.assertEqual(machine.reg_read(UC_ARM64_REG_SP), stack + 48)
            machine.emu_stop()
        uc.hook_add(UC_HOOK_CODE, hook)
        uc.emu_start(TAP_RELEASE - 28, 0, count=30)
        self.assertEqual(calls, [DELAYED_QUEUE])

    def test_null_dispatcher_still_takes_original_failure_path(self):
        uc, stack = self.machine()
        uc.reg_write(UC_ARM64_REG_X19, 0)
        calls = []
        def hook(machine, address, size, user):
            if address in (TAP_RELEASE + 20, DELAYED_QUEUE):
                calls.append(address)
                machine.emu_stop()
        uc.hook_add(UC_HOOK_CODE, hook)
        uc.emu_start(TAP_RELEASE - 28, 0, count=30)
        self.assertEqual(calls, [TAP_RELEASE + 20])
        self.assertEqual(uc.reg_read(UC_ARM64_REG_SP), stack)

    def test_noisy_contacts_and_existing_gestures_reach_original_callback(self):
        root = Path(__file__).resolve().parents[1]
        java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip())
        with tempfile.TemporaryDirectory() as out:
            subprocess.run([str(java / "bin/javac"), "--release", "8", "-d", out,
                            str(root / "port-client/src/dev/beampro/port/TrackpadCompat.java"),
                            str(root / "tools/TrackpadCompatTest.java")], check=True)
            result = subprocess.check_output([str(java / "bin/java"), "-cp", out, "TrackpadCompatTest"], text=True)
            self.assertIn("PASS: noisy tap, noisy hold, drag", result)

    def test_both_orientation_hooks_restore_original_dex(self):
        from trackpad_taps import patch_trackpad_dex
        java = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip())
        with zipfile.ZipFile(BASELINE) as archive:
            original = archive.read("classes.dex")
        with tempfile.TemporaryDirectory() as out:
            patched = patch_trackpad_dex(original, out, java, dict(os.environ, JAVA_HOME=str(java)))
            self.assertNotEqual(patched, original)


if __name__ == "__main__": unittest.main()
