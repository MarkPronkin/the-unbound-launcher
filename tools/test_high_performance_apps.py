"""Execute both real ARM64 launch checks with existing high-performance windows."""
import struct
import sys
import unittest
import zipfile

from app_color import LIBRARY, patch_color_native
from app_limit import TARGETS as WINDOW_TARGETS
from app_surface_routing import patch_surface_routing
from build_embedded_adb import BASE, ROOT
from experimental_stereo import patch_stereo_native
from high_performance_apps import TARGETS, patch_high_performance_apps, verify_high_performance_apps
from overlay_routing import patch_overlay_routing
from package_identity import patch_binary_identity
from recording_duration import patch_recording_duration
from startup_tutorial import patch_startup_tutorial
from xreal_telemetry import file_offset

sys.path.insert(0, str(ROOT / "artifacts/tools/python"))
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (
    UC_ARM64_REG_X0, UC_ARM64_REG_X8, UC_ARM64_REG_X19, UC_ARM64_REG_X21,
    UC_ARM64_REG_X22, UC_ARM64_REG_X23, UC_ARM64_REG_X25, UC_ARM64_REG_X26,
    UC_ARM64_REG_X27, UC_ARM64_REG_X28,
    UC_ARM64_REG_X29, UC_ARM64_REG_X30, UC_ARM64_REG_SP, UC_ARM64_REG_PC,
)


class HighPerformanceAppsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(BASE) as archive:
            cls.base = archive.read(LIBRARY)
            cls.english = archive.read("assets/bin/Data/4a275b4a1bdadb946afd8a7e154003db")
        cls.original = patch_recording_duration(patch_surface_routing(patch_overlay_routing(
            patch_stereo_native(patch_startup_tutorial(patch_color_native(cls.base))))))
        cls.patched = patch_high_performance_apps(cls.original)

    def machine(self, blob):
        cpu = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        code, size = 0x33BE000, 0x7000
        cpu.mem_map(code, size)
        at = file_offset(blob, code)
        cpu.mem_write(code, blob[at:at + size])
        cpu.mem_map(0x700000, 0x10000)
        cpu.mem_map(0x458E000, 0x1000)
        cpu.mem_write(0x458E9E0, struct.pack("<Q", 0x700100))
        cpu.mem_map(0x45EA000, 0x1000)  # Metadata loads beside the total-window checks.
        cpu.reg_write(UC_ARM64_REG_SP, 0x70F000)
        return cpu

    def launch_decision(self, blob, intent, existing, app_flag, global_flag, limit_flag):
        cpu = self.machine(blob)
        # Application2DManager and Unity class initialization have completed.
        for slot, value in ((0x700100, 0x701000), (0x700120, 0x701100),
                            (0x700140, 0x701200), (0x7010B8, 0x702000),
                            (0x704028, 0x705000)):
            cpu.mem_write(slot, struct.pack("<Q", value))
        for klass in (0x701000, 0x701100, 0x701200):
            cpu.mem_write(klass + 0xE0, struct.pack("<I", 1))
        cpu.mem_write(0x702001, bytes([global_flag]))
        cpu.mem_write(0x702004, bytes([limit_flag]))
        cpu.mem_write(0x703051, bytes([app_flag]))
        for register, value in {
            UC_ARM64_REG_X19: 0x704000, UC_ARM64_REG_X21: 0x703000,
            UC_ARM64_REG_X22: 0x700100, UC_ARM64_REG_X25: 0x700120,
            UC_ARM64_REG_X26: 0x700140,
        }.items():
            cpu.reg_write(register, value)
        if intent:
            cpu.reg_write(UC_ARM64_REG_X22, 0x703000)
            cpu.reg_write(UC_ARM64_REG_X29, 0x700100)
            cpu.reg_write(UC_ARM64_REG_X27, 0x700120)
            cpu.reg_write(UC_ARM64_REG_X28, 0x700140)
            start, allowed, blocked = 0x33BF4D4, 0x33BF648, 0x33BF558
        else:
            start, allowed, blocked = 0x33C3B14, 0x33C3BF4, 0x33C3BA0
        for address in (0x2252B48, 0x3097758):
            cpu.mem_map(address & ~0xFFF, 0x1000)
        result = {"outcome": None, "window_queries": 0}

        def hook(machine, address, size, data):
            if address in (allowed, blocked):
                result["outcome"] = "launch" if address == allowed else "replace"
                machine.emu_stop()
            elif address in (0x2252B48, 0x3097758):
                if address == 0x2252B48:
                    machine.reg_write(UC_ARM64_REG_X0, 0x706000)
                else:
                    result["window_queries"] += 1
                    machine.mem_write(0x705018, struct.pack("<I", existing))
                    machine.reg_write(UC_ARM64_REG_X0, int(existing > 0))
                machine.reg_write(UC_ARM64_REG_PC, machine.reg_read(UC_ARM64_REG_X30))

        cpu.hook_add(UC_HOOK_CODE, hook)
        cpu.emu_start(start, allowed + 4, count=150)
        self.assertIsNotNone(result["outcome"])
        return result

    def test_new_app_keeps_existing_high_performance_apps_in_both_launch_paths(self):
        self.assertIn(b'"evapro_close_multilayer_app_window":"Opening a new app will close '
                      b'the current high-performance app. Continue to open?"', self.english)
        for intent in (False, True):
            for existing in (0, 1, 2, 9):
                for app_flag, global_flag in ((0, 0), (1, 0), (0, 1), (1, 1)):
                    for limit_flag in (0, 1):
                        with self.subTest(intent=intent, existing=existing, app=app_flag,
                                          globally_enabled=global_flag, limit_enabled=limit_flag):
                            arguments = (intent, existing, app_flag, global_flag, limit_flag)
                            original = self.launch_decision(self.original, *arguments)
                            should_replace = existing > 0 and (app_flag or global_flag) and limit_flag
                            self.assertEqual(original["outcome"], "replace" if should_replace else "launch")
                            patched = self.launch_decision(self.patched, *arguments)
                            self.assertEqual(patched["outcome"], "launch")
                            self.assertEqual(patched["window_queries"], original["window_queries"])

    def test_ten_window_limit_still_takes_replacement_path_when_full(self):
        for blob in (self.original, self.patched):
            for intent in (False, True):
                for existing in (0, 1, 9, 10, 11):
                    with self.subTest(patched=blob is self.patched, intent=intent, existing=existing):
                        cpu = self.machine(blob)
                        cpu.reg_write(UC_ARM64_REG_X23, existing)
                        cpu.reg_write(UC_ARM64_REG_X8, 10)
                        start, allowed, full = ((0x33BF498, 0x33BF4A8, 0x33BF5F0) if intent
                                                else (0x33C39A8, 0x33C39B8, 0x33C3A94))
                        def stop(machine, address, size, data):
                            if address in (allowed, full):
                                machine.emu_stop()
                        cpu.hook_add(UC_HOOK_CODE, stop)
                        cpu.emu_start(start, full + 4, count=10)
                        self.assertEqual(cpu.reg_read(UC_ARM64_REG_PC), full if existing >= 10 else allowed)
        for target in WINDOW_TARGETS:
            at = file_offset(self.patched, target["address"])
            self.assertEqual(self.patched[at:at + 4].hex(), target["replacement"])

    def test_exact_scope_composes_with_native_fixes_and_package_identity(self):
        restored = bytearray(self.patched)
        for target in TARGETS:
            at = file_offset(restored, target["address"])
            restored[at:at + 4] = bytes.fromhex(target["original"])
        self.assertEqual(restored, self.original)
        relocated = patch_binary_identity(LIBRARY, self.patched)
        self.assertEqual(patch_binary_identity(LIBRARY, relocated, restore=True), self.patched)
        restored = patch_high_performance_apps(self.patched, restore=True)
        restored = patch_recording_duration(restored, restore=True)
        restored = patch_surface_routing(restored, restore=True)
        restored = patch_overlay_routing(restored, restore=True)
        restored = patch_stereo_native(restored, restore=True)
        restored = patch_startup_tutorial(restored, restore=True)
        self.assertEqual(patch_color_native(restored, restore=True), self.base)
        self.assertTrue(verify_high_performance_apps(self.patched)["separate_high_performance_app_limit_removed"])

    def test_rejects_wrong_repeated_partial_and_unrelated_changes(self):
        for blob in (self.base, self.patched):
            with self.assertRaisesRegex(ValueError, "Unexpected native library"):
                patch_high_performance_apps(blob)
        corrupt = bytearray(self.patched)
        corrupt[-1] ^= 1
        with self.assertRaisesRegex(ValueError, "Unrelated native changes"):
            verify_high_performance_apps(corrupt)
        for target in TARGETS:
            partial = bytearray(self.patched)
            at = file_offset(partial, target["address"])
            partial[at:at + 4] = bytes.fromhex(target["original"])
            with self.assertRaisesRegex(ValueError, "Unexpected high-performance app instructions"):
                verify_high_performance_apps(partial)


if __name__ == "__main__":
    unittest.main()
