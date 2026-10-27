"""Execute the real ARM64 startup decisions and audit the entire library scope."""
from pathlib import Path
import sys
import unittest
import zipfile

from app_color import LIBRARY, patch_color_native
from build_embedded_adb import BASE
from startup_tutorial import NEED_OPEN_BASIC_TUTORIAL, ORIGINAL, patch_startup_tutorial
from xreal_telemetry import file_offset

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "artifacts/tools/python"))
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM


def execute(blob, start, stop, registers):
    # This host cannot run Unicorn's JIT. Interpret only the four instructions
    # used by the predicate and its existing callers, decoded from the ELF.
    cpu = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    cpu.detail = True
    pc = start

    def read(register):
        name = cpu.reg_name(register)
        if name in ("wzr", "xzr"):
            return 0
        return registers["x" + name[1:]] & 0xFFFFFFFF if name.startswith("w") else registers[name]

    for _ in range(12):
        if pc == stop:
            return
        offset = file_offset(blob, pc)
        instruction = next(cpu.disasm(blob[offset:offset + 4], pc))
        operands = instruction.operands
        next_pc = pc + 4
        if instruction.mnemonic == "mov":
            name = cpu.reg_name(operands[0].reg)
            assert name.startswith("w"), name
            registers["x" + name[1:]] = read(operands[1].reg) & 0xFFFFFFFF
        elif instruction.mnemonic == "ret":
            next_pc = registers["x30"]
        elif instruction.mnemonic == "bl":
            registers["x30"] = next_pc
            next_pc = operands[0].imm
        elif instruction.mnemonic == "tbz":
            if not read(operands[0].reg) & (1 << operands[1].imm):
                next_pc = operands[2].imm
        else:
            raise AssertionError(f"Unexpected instruction: {instruction.mnemonic} {instruction.op_str}")
        pc = next_pc
    raise AssertionError(f"Did not reach expected startup path {stop:#x}; stopped at {pc:#x}")


class StartupTutorialTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(BASE) as archive:
            cls.baseline = archive.read(LIBRARY)
        cls.colored = patch_color_native(cls.baseline)
        cls.patched = patch_startup_tutorial(cls.colored)

    def test_exactly_one_predicate_changes_and_prior_native_fixes_survive(self):
        offset = file_offset(self.colored, NEED_OPEN_BASIC_TUTORIAL)
        self.assertEqual(self.patched[:offset], self.colored[:offset])
        self.assertEqual(self.patched[offset + 8:], self.colored[offset + 8:])
        restored = patch_startup_tutorial(self.patched, restore=True)
        self.assertEqual(restored, self.colored)
        self.assertEqual(patch_color_native(restored, restore=True), self.baseline)
        # These direct manual-entry methods and notification branches remain intact.
        for start, end in [(0x2BDC798, 0x2BDCB70), (0x2BDCB70, 0x2BDCC34),
                           (0x2BDD0A4, 0x2BDD254)]:
            a, b = file_offset(self.patched, start), file_offset(self.patched, end)
            self.assertEqual(self.patched[a:b], self.colored[a:b])

    def test_false_without_reading_device_or_saved_state_and_preserves_abi(self):
        # No data or stack is mapped: fresh installs and saved settings cannot
        # affect the result, and the predicate cannot change completion data.
        sentinel = NEED_OPEN_BASIC_TUTORIAL + 0x100
        preserved = {f"x{index}": 0x80000000 + index * 16 for index in range(19, 30)}
        preserved.update(sp=0x90000000, x30=sentinel)
        for incoming in (0, 1, 0xFFFFFFFFFFFFFFFF):
            with self.subTest(incoming=incoming):
                registers = dict(preserved, x0=incoming)
                execute(self.patched, NEED_OPEN_BASIC_TUTORIAL, sentinel, registers)
                self.assertEqual(registers, dict(preserved, x0=0))

    def test_real_startup_calls_take_existing_no_tutorial_branches(self):
        # Execute the stock BL + TBZ with the patched predicate. The first
        # destination enables the controller; the coroutine destination exits.
        # Neither path reaches HideMainMenu, SetIgnoreMenuCommand or StartBasic.
        for call, destination in [(0x2BDBF80, 0x2BDC188), (0x2BDFD34, 0x2BDFF78)]:
            with self.subTest(call=hex(call)):
                registers = {"x0": 0xFFFFFFFFFFFFFFFF, "x30": 0}
                execute(self.patched, call, destination, registers)
                self.assertEqual(registers["x0"], 0)

    def test_rejects_unexpected_repeated_partial_or_unrelated_changes(self):
        for blob in (self.baseline, self.patched):
            with self.assertRaisesRegex(ValueError, "Unexpected native library"):
                patch_startup_tutorial(blob)
        for original, restore in [(self.colored, False), (self.patched, True)]:
            corrupt = bytearray(original)
            corrupt[-1] ^= 1
            with self.assertRaises(ValueError):
                patch_startup_tutorial(corrupt, restore=restore)
        partial = bytearray(self.patched)
        offset = file_offset(partial, NEED_OPEN_BASIC_TUTORIAL)
        partial[offset + 4:offset + 8] = ORIGINAL[4:]
        with self.assertRaisesRegex(ValueError, "predicate instructions"):
            patch_startup_tutorial(partial, restore=True)


if __name__ == "__main__":
    unittest.main()
