"""Execute the shipped app-open callback, mode getters and layout dispatcher."""
import struct
import sys
import unittest
import zipfile

from anchor_windows import (FIRST_REBUILD, IN_ANCHOR_MODE, LAYOUT_CALL,
                            REBUILD_LAYOUT, TARGETS, WRAPPER,
                            patch_anchor_windows, verify_anchor_windows)
from app_color import LIBRARY, patch_color_native
from app_surface_routing import patch_surface_routing
from build_embedded_adb import BASE, ROOT
from experimental_stereo import patch_stereo_native
from high_performance_apps import patch_high_performance_apps
from overlay_routing import patch_overlay_routing
from package_identity import patch_binary_identity
from recording_duration import patch_recording_duration
from startup_tutorial import patch_startup_tutorial
from xreal_telemetry import RETURN, file_offset, verify_library

sys.path.insert(0, str(ROOT / "artifacts/tools/python"))
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (
    UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X19,
    UC_ARM64_REG_X20, UC_ARM64_REG_X21, UC_ARM64_REG_X30,
    UC_ARM64_REG_SP, UC_ARM64_REG_PC,
)


class AnchorWindowsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(BASE) as archive:
            cls.base = archive.read(LIBRARY)
        cls.original = patch_high_performance_apps(patch_recording_duration(
            patch_surface_routing(patch_overlay_routing(patch_stereo_native(
                patch_startup_tutorial(patch_color_native(cls.base)))))))
        cls.patched = patch_anchor_windows(cls.original)

    def machine(self, blob, mode, existing, first_layout=True):
        cpu = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        for start, size in ((0x34F6000, 0x1000), (0x16FE000, 0x3000),
                            (WRAPPER & ~0xFFF, 0x1000)):
            cpu.mem_map(start, size)
            at = file_offset(blob, start)
            cpu.mem_write(start, blob[at:at + size])
        for start, size in ((0x700000, 0x10000), (0x8000000, 0x1000),
                            (0x4904000, 0x1000), (0x48F3000, 0x1000),
                            (0x45EF000, 0x1000), (0x454A000, 0x1000),
                            (0x4568000, 0x1000), (0x4594000, 0x1000),
                            (0x1B64000, 0x1000), (0x22E2000, 0x1000),
                            (0x1704000, 0x1000)):
            cpu.mem_map(start, size)
        # Initialized IL2CPP class and method metadata. The actual mode getters
        # read sCurrentLayoutMode, including the runtime tracking-mode mapping.
        cpu.mem_write(0x4904000, b"\x01" * 0x1000)
        cpu.mem_write(0x48F3000, b"\x01" * 0x1000)
        for address, value in ((0x45EFCC0, 0x700100), (0x700100, 0x700300),
                               (0x7003B8, 0x701000),
                               (0x701010, 0x702000), (0x4594BC8, 0x700200),
                               (0x4568480, 0x700208), (0x454AEF8, 0x700100)):
            cpu.mem_write(address, struct.pack("<Q", value))
        cpu.mem_write(0x7003E0, struct.pack("<I", 1))
        cpu.mem_write(0x701018, struct.pack("<I", mode))
        cpu.mem_write(0x702018, struct.pack("<I", existing + 1))
        cpu.mem_write(0x703058, bytes([first_layout]))
        windows = [0x704000 + index * 0x100 for index in range(existing)] + [0x703000]
        # Distinct position/rotation/scale records stand in for Unity transforms
        # at the overlap/retrieve boundary; no Android or Unity runtime needed.
        for index, window in enumerate(windows):
            cpu.mem_write(window + 0x80, struct.pack("<10f", *(
                index + component / 10 for component in range(10))))
        cpu.reg_write(UC_ARM64_REG_SP, 0x70F000)
        cpu.reg_write(UC_ARM64_REG_X30, 0x8000000)
        sentinels = {UC_ARM64_REG_X19: 0x1919, UC_ARM64_REG_X20: 0x2020,
                     UC_ARM64_REG_X21: 0x2121}
        for register, value in sentinels.items():
            cpu.reg_write(register, value)
        calls = []

        def hook(machine, address, size, data):
            if address == REBUILD_LAYOUT:
                calls.append(("rebuild", machine.reg_read(UC_ARM64_REG_X0),
                              machine.reg_read(UC_ARM64_REG_X1)))
            elif address == 0x1B64FA4:  # List<EvaProAppSeparateWidgetContent>.get_Item
                machine.reg_write(UC_ARM64_REG_X0, windows[machine.reg_read(UC_ARM64_REG_X1)])
                machine.reg_write(UC_ARM64_REG_PC, machine.reg_read(UC_ARM64_REG_X30))
            elif address == 0x17046D4:  # Pairwise AvoidWidgetOverlap moves second window.
                window = machine.reg_read(UC_ARM64_REG_X1)
                calls.append(("overlap", window))
                machine.mem_write(window + 0x80, b"\xEE" * 40)
                machine.reg_write(UC_ARM64_REG_PC, machine.reg_read(UC_ARM64_REG_X30))
            elif address == 0x22E2C10:  # GlassConfig.get_SingleEye
                machine.reg_write(UC_ARM64_REG_X0, 0)
                machine.reg_write(UC_ARM64_REG_PC, machine.reg_read(UC_ARM64_REG_X30))
            elif address == 0x170074C:  # Smooth-follow layout retrieves all windows.
                calls.append(("retrieve",))
                for window in windows:
                    machine.mem_write(window + 0x80, b"\xDD" * 40)
                machine.reg_write(UC_ARM64_REG_PC, machine.reg_read(UC_ARM64_REG_X30))

        cpu.hook_add(UC_HOOK_CODE, hook)
        return cpu, windows, calls, sentinels

    def open_window(self, blob, mode, existing, first_layout=True):
        cpu, windows, calls, sentinels = self.machine(blob, mode, existing, first_layout)
        before = [bytes(cpu.mem_read(window + 0x80, 40)) for window in windows]
        cpu.reg_write(UC_ARM64_REG_X0, 0x703000)
        cpu.emu_start(FIRST_REBUILD, 0x8000000, count=2000)
        self.assertEqual(cpu.reg_read(UC_ARM64_REG_PC), 0x8000000)
        self.assertEqual(cpu.reg_read(UC_ARM64_REG_SP), 0x70F000)
        for register, value in sentinels.items():
            self.assertEqual(cpu.reg_read(register), value)
        self.assertEqual(cpu.mem_read(0x703058, 1), b"\0")  # Stock first-layout cleanup.
        after = [bytes(cpu.mem_read(window + 0x80, 40)) for window in windows]
        return before, after, calls

    def test_opening_apps_in_anchor_mode_preserves_every_existing_transform(self):
        for existing in (0, 1, 2, 5, 9):
            with self.subTest(existing=existing):
                before, after, calls = self.open_window(self.patched, 2, existing)
                self.assertEqual(after, before)  # Includes the new window's initial pose.
                self.assertEqual(calls, [])
                old_before, old_after, old_calls = self.open_window(self.original, 2, existing)
                self.assertEqual(old_calls[0], ("rebuild", 1, 0))
                if existing >= 2:
                    self.assertNotEqual(old_after[:-1], old_before[:-1])

    def test_smooth_follow_and_unknown_mode_keep_the_original_layout(self):
        for mode in (0, 1):
            for existing in (0, 1, 2, 9):
                with self.subTest(mode=mode, existing=existing):
                    self.assertEqual(self.open_window(self.patched, mode, existing),
                                     self.open_window(self.original, mode, existing))

    def test_mode_is_read_for_each_open_and_duplicate_callbacks_do_not_reflow(self):
        cpu, windows, calls, _ = self.machine(self.patched, 2, 2)
        for mode, first, expected_calls in ((2, True, 0), (1, False, 0),
                                             (1, True, 2), (2, True, 0)):
            with self.subTest(mode=mode, first=first):
                calls.clear()
                cpu.mem_write(0x701018, struct.pack("<I", mode))
                cpu.mem_write(0x703058, bytes([first]))
                cpu.reg_write(UC_ARM64_REG_X0, 0x703000)
                cpu.reg_write(UC_ARM64_REG_X30, 0x8000000)
                cpu.emu_start(FIRST_REBUILD, 0x8000000, count=2000)
                self.assertEqual(len(calls), expected_calls)
                self.assertEqual(cpu.mem_read(0x703058, 1), b"\0")
                self.assertEqual(cpu.reg_read(UC_ARM64_REG_SP), 0x70F000)

    def test_exact_scope_composes_with_all_native_fixes_and_package_identity(self):
        restored = bytearray(self.patched)
        for target in TARGETS:
            at = file_offset(restored, target["address"])
            original = bytes.fromhex(target["original"])
            restored[at:at + len(original)] = original
        self.assertEqual(restored, self.original)
        self.assertEqual(self.patched[WRAPPER - 4:WRAPPER], RETURN)
        self.assertEqual(verify_library(self.patched), 40)
        self.assertEqual(verify_anchor_windows(self.patched)["patch_bytes"], 36)
        relocated = patch_binary_identity(LIBRARY, self.patched)
        self.assertEqual(patch_binary_identity(LIBRARY, relocated, restore=True), self.patched)
        restored = patch_anchor_windows(self.patched, restore=True)
        for patch in (patch_high_performance_apps, patch_recording_duration,
                      patch_surface_routing, patch_overlay_routing, patch_stereo_native,
                      patch_startup_tutorial, patch_color_native):
            restored = patch(restored, restore=True)
        self.assertEqual(restored, self.base)

    def test_rejects_wrong_baseline_unrelated_changes_and_partial_patch(self):
        for blob in (self.base, self.patched):
            with self.assertRaises(ValueError):
                patch_anchor_windows(blob)
        for blob, restore in ((self.original, False), (self.patched, True)):
            damaged = bytearray(blob)
            damaged[-1] ^= 1
            with self.assertRaises(ValueError):
                patch_anchor_windows(damaged, restore=restore)
        for target in TARGETS:
            damaged = bytearray(self.patched)
            damaged[file_offset(damaged, target["address"])] ^= 1
            with self.assertRaises(ValueError):
                patch_anchor_windows(damaged, restore=True)
        damaged = bytearray(self.patched)
        damaged[file_offset(damaged, WRAPPER - 4)] ^= 1
        with self.assertRaises(ValueError):
            patch_anchor_windows(damaged, restore=True)


if __name__ == "__main__":
    unittest.main()
