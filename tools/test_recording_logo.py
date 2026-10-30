"""Execute video logo setup and the real ARM64 null-sprite rendering path."""
import struct
import sys
import unittest
import zipfile

from anchor_windows import patch_anchor_windows
from app_color import LIBRARY, patch_color_native
from app_surface_routing import patch_surface_routing
from build_embedded_adb import BASE, ROOT
from experimental_stereo import patch_stereo_native
from high_performance_apps import patch_high_performance_apps
from overlay_routing import patch_overlay_routing
from package_identity import patch_binary_identity
from recording_duration import patch_recording_duration
from recording_logo import TARGETS, patch_recording_logo, verify_recording_logo
from startup_tutorial import patch_startup_tutorial
from xreal_telemetry import file_offset

sys.path.insert(0, str(ROOT / "artifacts/tools/python"))
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (
    UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X19, UC_ARM64_REG_X20,
    UC_ARM64_REG_X21, UC_ARM64_REG_X22, UC_ARM64_REG_X23, UC_ARM64_REG_X24,
    UC_ARM64_REG_X25, UC_ARM64_REG_X30, UC_ARM64_REG_SP, UC_ARM64_REG_PC,
    UC_ARM64_REG_D8, UC_ARM64_REG_D15,
)

PRIOR_PATCHES = (patch_color_native, patch_startup_tutorial, patch_stereo_native,
                 patch_overlay_routing, patch_surface_routing, patch_recording_duration,
                 patch_high_performance_apps, patch_anchor_windows)


class RecordingLogoTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(BASE) as archive:
            cls.base = archive.read(LIBRARY)
        cls.original = cls.base
        for patch in PRIOR_PATCHES:
            cls.original = patch(cls.original)
        cls.patched = patch_recording_logo(cls.original)

    def machine(self, blob):
        cpu = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        for start, size in ((0xF07000, 0x5000), (0x1679000, 0x1000)):
            cpu.mem_map(start, size)
            at = file_offset(blob, start)
            cpu.mem_write(start, blob[at:at + size])
        for page, size in ((0x700000, 0x20000), (0x45DC000, 0x1000),
                           (0x454A000, 0x1000), (0x3574000, 0x1000),
                           (0x48EF000, 0x1000), (0x4555000, 0x1000),
                           (0xD60000, 0x1000), (0x3471000, 0x1000)):
            cpu.mem_map(page, size)
        # Initialized generic method/class metadata and Vector3 scale data.
        for address, value in ((0x45DC458, 0x705000), (0x705000, 0x706000),
                               (0x7060B8, 0x707000), (0x454A170, 0x701008),
                               (0x701008, 0x701000), (0x701020, 0x702000),
                               (0x7020C0, 0x703000), (0x703000, 0x704000),
                               (0x4555F88, 0x705008), (0x705008, 0x706100)):
            cpu.mem_write(address, struct.pack("<Q", value))
        cpu.mem_write(0x704132, b"\x01")
        cpu.mem_write(0x7061E0, struct.pack("<I", 1))
        cpu.mem_write(0x48EF53A, b"\x01")
        cpu.mem_write(0x70700C, struct.pack("<3f", 1, 2, 3))
        cpu.mem_write(0x35743B4, struct.pack("<f", 0.25))
        cpu.reg_write(UC_ARM64_REG_SP, 0x71F000)
        state = {"overlay": False, "compared_sprite": None}

        def hook(machine, address, size, data):
            if address == 0xD602C4:  # Generic builder cast; x0 remains the builder.
                machine.reg_write(UC_ARM64_REG_PC, machine.reg_read(UC_ARM64_REG_X30))
            elif address == 0x3471878:  # UnityEngine.Object.op_Equality(sprite, null).
                sprite = machine.reg_read(UC_ARM64_REG_X0)
                self.assertEqual(machine.reg_read(UC_ARM64_REG_X1), 0)
                state["compared_sprite"] = sprite
                machine.reg_write(UC_ARM64_REG_X0, int(sprite == 0))
                machine.reg_write(UC_ARM64_REG_PC, machine.reg_read(UC_ARM64_REG_X30))
            elif address == 0xF07CA8:  # First logo construction instruction after null guard.
                state["overlay"] = True
                machine.emu_stop()

        cpu.hook_add(UC_HOOK_CODE, hook)
        return cpu, state

    def video_sprite(self, cpu, selected, eye):
        view, builder, tool = 0x709000, 0x700000, 0x708000
        cpu.mem_write(view + 0xB8, struct.pack("<Q", 0x70A000))
        cpu.reg_write(UC_ARM64_REG_X19, view)
        cpu.reg_write(UC_ARM64_REG_X24, selected)
        cpu.reg_write(UC_ARM64_REG_X0, int(eye))
        # Execute the actual Eye/model selection and the video-specific
        # argument setup, including shared Builder.SetLogo (not a stub).
        cpu.emu_start(0xF0B124, 0xF0B12C, count=5)
        cpu.reg_write(UC_ARM64_REG_X21, builder)
        cpu.emu_start(0xF0B210, 0xF0B254, count=100)
        self.assertEqual(cpu.reg_read(UC_ARM64_REG_PC), 0xF0B254)
        self.assertEqual(cpu.reg_read(UC_ARM64_REG_X0), builder)
        self.assertEqual(struct.unpack("<3f", cpu.mem_read(builder + 0x30, 12)),
                         (0.25, 0.5, 0.75))
        # Execute the stock Create instructions that copy sprite and scale
        # to the capture tool, after its allocation and other field setup.
        cpu.reg_write(UC_ARM64_REG_X19, builder)
        cpu.reg_write(UC_ARM64_REG_X20, tool)
        cpu.emu_start(0x16791CC, 0x16791E8, count=10)
        self.assertEqual(cpu.mem_read(tool + 0x24, 12), cpu.mem_read(builder + 0x30, 12))
        return tool, struct.unpack("<Q", cpu.mem_read(tool + 0x30, 8))[0]

    def render_logo(self, cpu, tool, *, from_video=False):
        preserved = {UC_ARM64_REG_X19: 19, UC_ARM64_REG_X20: 20,
                     UC_ARM64_REG_X21: 21, UC_ARM64_REG_X22: 22,
                     UC_ARM64_REG_X23: 23, UC_ARM64_REG_X24: 24,
                     UC_ARM64_REG_X25: 25, UC_ARM64_REG_D8: 0x12345678,
                     UC_ARM64_REG_D15: 0x87654321, UC_ARM64_REG_SP: 0x71F000}
        for register, value in preserved.items():
            cpu.reg_write(register, value)
        cpu.reg_write(UC_ARM64_REG_X0, tool)
        cpu.reg_write(UC_ARM64_REG_X1, 0x70B000)  # Capture camera.
        cpu.reg_write(UC_ARM64_REG_X30, 0x710000)
        start = 0xF07BFC
        if from_video:
            # StartRecordingAsync tail-dispatches the virtual SetLogo method
            # after encoder start and camera-mask setup. Execute that actual
            # dispatch with one capture camera and its initialized vtable.
            for address, value in ((tool, 0x70D000), (0x70D188, 0xF07BFC),
                                   (tool + 0x48, 0x70E000), (0x70E018, 0x70E100),
                                   (0x70E170, 0x70E200), (0x70E220, 0x70E300),
                                   (0x70E320, 0x70B000)):
                cpu.mem_write(address, struct.pack("<Q", value))
            cpu.mem_write(0x70E318, struct.pack("<I", 1))
            stack = 0x71EFD0
            cpu.mem_write(stack, struct.pack("<Q", preserved[UC_ARM64_REG_X22]))
            cpu.mem_write(stack + 0x10, struct.pack("<2Q", preserved[UC_ARM64_REG_X21],
                                                 preserved[UC_ARM64_REG_X20]))
            cpu.mem_write(stack + 0x20, struct.pack("<2Q", preserved[UC_ARM64_REG_X19], 0x710000))
            cpu.reg_write(UC_ARM64_REG_X19, tool)
            cpu.reg_write(UC_ARM64_REG_SP, stack)
            start = 0xF0959C
        cpu.emu_start(start, 0x710000, count=100)
        if cpu.reg_read(UC_ARM64_REG_PC) == 0x710000:
            for register, value in preserved.items():
                self.assertEqual(cpu.reg_read(register), value)

    def test_eye_and_other_video_logos_skip_overlay_with_stock_negative_control(self):
        for eye, selected in ((True, 0x70C000), (False, 0x70C000), (False, 0)):
            for patched in (False, True):
                with self.subTest(eye=eye, selected=selected, patched=patched):
                    cpu, state = self.machine(self.patched if patched else self.original)
                    tool, sprite = self.video_sprite(cpu, selected, eye)
                    expected = 0 if patched else (0x70A000 if eye else selected)
                    self.assertEqual(sprite, expected)
                    self.render_logo(cpu, tool, from_video=True)
                    self.assertEqual(state["compared_sprite"], expected)
                    self.assertEqual(state["overlay"], bool(expected))
                    if not expected:
                        self.assertEqual(cpu.reg_read(UC_ARM64_REG_PC), 0x710000)

    def test_shared_photo_logo_renderer_remains_available(self):
        cpu, state = self.machine(self.patched)
        tool = 0x708000
        cpu.mem_write(tool + 0x30, struct.pack("<Q", 0x70A000))
        self.render_logo(cpu, tool)
        self.assertTrue(state["overlay"])

    def test_exact_scope_and_composition_with_prior_fixes_and_identity(self):
        restored = bytearray(self.patched)
        for target in TARGETS:
            at = file_offset(restored, target["address"])
            restored[at:at + 4] = bytes.fromhex(target["original"])
        self.assertEqual(restored, self.original)
        relocated = patch_binary_identity(LIBRARY, self.patched)
        self.assertEqual(patch_binary_identity(LIBRARY, relocated, restore=True), self.patched)
        restored = patch_recording_logo(self.patched, restore=True)
        for patch in reversed(PRIOR_PATCHES):
            restored = patch(restored, restore=True)
        self.assertEqual(restored, self.base)
        self.assertEqual(verify_recording_logo(self.patched)["patch_bytes"], 4)

    def test_rejects_wrong_repeated_missing_and_unrelated_changes(self):
        for blob in (self.base, self.patched):
            with self.assertRaisesRegex(ValueError, "Unexpected native library"):
                patch_recording_logo(blob)
        with self.assertRaisesRegex(ValueError, "Unexpected recording logo instructions"):
            verify_recording_logo(self.original)
        corrupt = bytearray(self.patched)
        corrupt[-1] ^= 1
        with self.assertRaisesRegex(ValueError, "Unrelated native changes"):
            verify_recording_logo(corrupt)


if __name__ == "__main__":
    unittest.main()
