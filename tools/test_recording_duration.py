"""Run the actual ARM64 recording timer across its former two-minute deadline."""
import struct
import sys
import unittest
import zipfile

from app_color import LIBRARY, patch_color_native
from app_surface_routing import patch_surface_routing
from build_embedded_adb import BASE, ROOT
from experimental_stereo import patch_stereo_native
from overlay_routing import patch_overlay_routing
from recording_duration import BEGIN, UPDATE, TARGETS, patch_recording_duration, verify_recording_duration
from startup_tutorial import patch_startup_tutorial
from xreal_telemetry import file_offset

sys.path.insert(0, str(ROOT / 'artifacts/tools/python'))
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (
    UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X19, UC_ARM64_REG_X20,
    UC_ARM64_REG_X21, UC_ARM64_REG_X30, UC_ARM64_REG_SP, UC_ARM64_REG_PC,
    UC_ARM64_REG_S0, UC_ARM64_REG_D8,
)


class RecordingDurationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(BASE) as archive:
            cls.base = archive.read(LIBRARY)
        cls.original = patch_surface_routing(patch_overlay_routing(patch_stereo_native(
            patch_startup_tutorial(patch_color_native(cls.base)))))
        cls.patched = patch_recording_duration(cls.original)

    def test_exact_scope_and_composition_with_prior_native_fixes(self):
        restored = bytearray(self.patched)
        for target in TARGETS:
            at = file_offset(restored, target['address'])
            restored[at:at + 4] = bytes.fromhex(target['original'])
        self.assertEqual(restored, self.original)
        self.assertEqual(patch_recording_duration(self.patched, restore=True), self.original)
        self.assertTrue(verify_recording_duration(self.patched)['fixed_time_limit_removed'])
        restored = patch_surface_routing(restored, restore=True)
        restored = patch_overlay_routing(restored, restore=True)
        restored = patch_stereo_native(restored, restore=True)
        restored = patch_startup_tutorial(restored, restore=True)
        self.assertEqual(patch_color_native(restored, restore=True), self.base)

    def test_rejects_wrong_repeated_partial_and_unrelated_changes(self):
        for blob in (self.base, self.patched):
            with self.assertRaisesRegex(ValueError, 'Unexpected native library'):
                patch_recording_duration(blob)
        corrupt = bytearray(self.patched)
        corrupt[-1] ^= 1
        with self.assertRaisesRegex(ValueError, 'Unrelated native changes'):
            verify_recording_duration(corrupt)
        for target in TARGETS:
            partial = bytearray(self.patched)
            at = file_offset(partial, target['address'])
            partial[at:at + 4] = bytes.fromhex(target['original'])
            with self.assertRaisesRegex(ValueError, 'Unexpected recording duration instructions'):
                verify_recording_duration(partial)

    def machine(self, blob, duration=120.0):
        cpu = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        code = (BEGIN & ~0xfff) - 0x1000
        cpu.mem_map(code, 0x3000)
        at = file_offset(blob, code)
        cpu.mem_write(code, bytes(blob[at:at + 0x3000]))
        cpu.mem_map(0x700000, 0x10000)
        cpu.mem_map(0x800000, 0x10000)
        cpu.mem_map(0x455c000, 0x1000)
        cpu.mem_map(0x48ef000, 0x1000)
        # Stock class-initialization guards and RecordSettings class metadata.
        cpu.mem_write(0x48ef554, b'\1')
        cpu.mem_write(0x48ef551, b'\1')
        cpu.mem_write(0x455c888, struct.pack('<Q', 0x700000))
        cpu.mem_write(0x700000, struct.pack('<Q', 0x701000))
        cpu.mem_write(0x7010e0, struct.pack('<I', 1))
        cpu.mem_write(0x7010b8, struct.pack('<Q', 0x702000))
        owner, text, animation = 0x703000, 0x704000, 0x705000
        cpu.mem_write(owner + 0x18, struct.pack('<f', duration))
        cpu.mem_write(owner + 0x20, struct.pack('<Q', text))
        cpu.mem_write(owner + 0x30, struct.pack('<Q', animation))
        cpu.mem_write(owner + 0x40, struct.pack('<Q', 0x706000))
        cpu.mem_write(text, struct.pack('<Q', 0x707000))
        cpu.mem_write(0x7075e8, struct.pack('<Q', 0x709000))
        cpu.mem_write(0x706018, struct.pack('<Q', 0x709010))
        state = {'now': 1000.0, 'stops': 0, 'active': [], 'formatted': [], 'text': 0}
        external = (0x324da5c, 0x324a7ac, 0x3486d28, 0x324d8bc, 0x3486d6c)
        for page in {address & ~0xfff for address in external}:
            cpu.mem_map(page, 0x1000)

        def float_result(value):
            cpu.reg_write(UC_ARM64_REG_S0, struct.unpack('<I', struct.pack('<f', value))[0])

        def hook(machine, address, size, data):
            if address == 0x324da5c:
                float_result(state['now'])
            elif address == 0x324a7ac:
                machine.reg_write(UC_ARM64_REG_X0, owner)
            elif address == 0x3486d28:
                state['active'].append(machine.reg_read(UC_ARM64_REG_X1))
            elif address == 0x324d8bc:
                float_result(0.3)
            elif address == 0x3486d6c:
                machine.reg_write(UC_ARM64_REG_X0, 1)
            elif address == 0xF0A188:
                state['formatted'].append(machine.reg_read(UC_ARM64_REG_X0))
                machine.reg_write(UC_ARM64_REG_X0, 0x708000)
            elif address == 0x709000:
                state['text'] += 1
            elif address == 0x709010:
                state['stops'] += 1
            else:
                return
            machine.reg_write(UC_ARM64_REG_PC, machine.reg_read(UC_ARM64_REG_X30))

        cpu.hook_add(UC_HOOK_CODE, hook)
        return cpu, owner, state

    def run_method(self, cpu, owner, method):
        sentinel = 0x70f000
        preserved = {UC_ARM64_REG_X19: 1919, UC_ARM64_REG_X20: 2020,
                     UC_ARM64_REG_X21: 2121, UC_ARM64_REG_D8: 0x12345678,
                     UC_ARM64_REG_SP: 0x808000}
        for register, value in preserved.items():
            cpu.reg_write(register, value)
        cpu.reg_write(UC_ARM64_REG_X0, owner)
        cpu.reg_write(UC_ARM64_REG_X30, sentinel)
        cpu.emu_start(method, sentinel, count=200)
        self.assertEqual(cpu.reg_read(UC_ARM64_REG_PC), sentinel)
        for register, value in preserved.items():
            self.assertEqual(cpu.reg_read(register), value)

    def test_stock_deadline_stops_recording_but_patched_timer_runs_for_hours(self):
        # Negative control: the unmodified code counts down and invokes Stop
        # at 120 seconds, proving the fixture reaches the real deadline path.
        cpu, owner, state = self.machine(self.original)
        self.run_method(cpu, owner, BEGIN)
        state['now'] = 1121.0
        self.run_method(cpu, owner, UPDATE)
        self.assertEqual(state['stops'], 1)

        for duration in (0.0, 30.0, 120.0, 3600.0):
            with self.subTest(serialized_or_debug_duration=duration):
                cpu, owner, state = self.machine(self.patched, duration)
                self.run_method(cpu, owner, BEGIN)
                self.assertEqual(struct.unpack('<f', cpu.mem_read(owner + 0x3c, 4))[0], 1000.0)
                for elapsed in (0, 1, 119, 120, 121, 3600, 10800):
                    state['now'] = 1000.0 + elapsed
                    self.run_method(cpu, owner, UPDATE)
                    self.assertEqual(state['formatted'][-1], elapsed)
                    self.assertEqual(state['stops'], 0)
                self.assertEqual(state['text'], 7)
                self.assertEqual(state['active'][0], 1)
                self.assertIn(0, state['active'][1:])  # Recording blink still runs.
                # Starting another recording resets elapsed time.
                state['now'] = 20000.0
                self.run_method(cpu, owner, BEGIN)
                self.run_method(cpu, owner, UPDATE)
                self.assertEqual(state['formatted'][-1], 0)

    def test_manual_stop_still_dispatches_its_event_and_stop_hides_status(self):
        # The click path and all of its event dispatch instructions are intact.
        for start, end in ((0xF0A31C, 0xF0A3C8), (0xF0B9E4, 0xF0BB60),
                           (0xF0BF2C, 0xF0C198), (0x22B9838, 0x22BA670)):
            a, b = file_offset(self.original, start), file_offset(self.original, end)
            self.assertEqual(self.patched[a:b], self.original[a:b])
        cpu, owner, state = self.machine(self.patched)
        self.run_method(cpu, owner, BEGIN)
        self.run_method(cpu, owner, 0xF0A31C)
        self.assertEqual(state['active'], [1, 0])


if __name__ == '__main__':
    unittest.main()
