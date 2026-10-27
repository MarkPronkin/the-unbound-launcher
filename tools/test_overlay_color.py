"""The native overlay path decodes sRGB without calling the rejected override."""
from pathlib import Path
import sys
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'artifacts/tools/python'))
from capstone import CS_ARCH_ARM64, CS_MODE_ARM, Cs

from build_embedded_adb import BASE
from display_mode_compat import patch_display_mode
from overlay_color import (APPEND, CAVE, COMPOSITION_IMAGE, DECODE, DECRYPT, FORMAT,
                           LENGTH, LIBRARY, ORIGINAL, ORIGINAL_SITES, REPLACEMENT,
                           RESUME, S2_CAVE, S2_IMAGE, S2_LEN, SAMPLE, SAMPLE_BYTES,
                           SAMPLE_HEAD, SHADER, SIMPLE, SITE, STRING,
                           TYPE2_BRANCH, TYPE2_LEN, TYPE3_CALL, TYPE3_CAVE, TYPE3_LEN, WARPED,
                           patch_overlay_color, verify_overlay_color)


def _linear(encoded):
    if encoded <= 0.04045:
        return encoded / 12.92
    return ((encoded + 0.055) / 1.055) ** 2.4


def _disasm(blob, address, size):
    cs = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    return list(cs.disasm(blob[address:address + size], address))


def _resolved_adds(insns):
    pages = {}
    found = []
    for insn in insns:
        if insn.mnemonic == 'adrp':
            register, page = (part.strip() for part in insn.op_str.split(','))
            pages[register] = int(page.replace('#', ''), 16)
        elif insn.mnemonic == 'add':
            parts = [part.strip() for part in insn.op_str.split(',')]
            if len(parts) == 3 and parts[0] == parts[1] and parts[0] in pages:
                found.append(pages[parts[0]] + int(parts[2].replace('#', ''), 16))
    return found


class OverlayColorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(BASE) as archive:
            cls.original = archive.read(LIBRARY)
        cls.patched = patch_overlay_color(cls.original)

    def test_shader_matches_srgb_and_keeps_the_sampler_contract(self):
        self.assertEqual(self.original[SHADER:SHADER + LENGTH], ORIGINAL)
        shader = self.patched[SHADER:SHADER + LENGTH]
        self.assertEqual(shader, REPLACEMENT)
        self.assertEqual(shader.strip(), REPLACEMENT.strip())
        text = shader.decode()
        self.assertIn('uniform highp samplerExternalOES srcTex', text)
        self.assertIn('in vec2 vTexcoord0', text)
        self.assertIn('lessThanEqual(c,vec3(.04045))', text)
        self.assertNotIn('outColor = vec4(srcColor.xyz,srcColor.a)', text)
        self.assertEqual(text.count('{'), text.count('}'))
        self.assertAlmostEqual(_linear(0.5), 0.214041140, places=6)
        self.assertAlmostEqual(_linear(0.04045), 0.04045 / 12.92, places=6)
        self.assertEqual(_linear(0.0), 0.0)
        self.assertEqual(_linear(1.0), 1.0)

    def test_only_audited_bytes_change_and_restoration_is_exact(self):
        changed = []
        for address, original in ORIGINAL_SITES.items():
            self.assertEqual(self.original[address:address + SITE], original)
            self.assertEqual(self.patched[address:address + SITE], b'\x1f\x20\x03\xd5' * 4)
            self.assertNotIn(bytes.fromhex('e1f79152'), self.patched[address:address + SITE])
            self.assertEqual(self.patched[address + SITE:address + 32], self.original[address + SITE:address + 32])
            changed.append(range(address, address + SITE))
        changed.append(range(SHADER, SHADER + LENGTH))
        changed.append(range(TYPE2_BRANCH, TYPE2_BRANCH + 4))
        changed.append(range(TYPE3_CALL, TYPE3_CALL + 4))
        changed.append(range(CAVE, CAVE + len(COMPOSITION_IMAGE)))
        changed.append(range(SAMPLE, SAMPLE + 4))
        changed.append(range(S2_CAVE, S2_CAVE + len(S2_IMAGE)))
        for index, (old, new) in enumerate(zip(self.original, self.patched)):
            if old != new and not any(index in span for span in changed):
                self.fail(f'unrelated byte changed at {index:#x}')
        self.assertEqual(self.patched[SAMPLE + 4:SAMPLE + len(SAMPLE_BYTES)], SAMPLE_BYTES[4:])
        self.assertEqual(int.from_bytes(self.original[SAMPLE:SAMPLE + 4], 'little'), SAMPLE_HEAD)
        self.assertEqual(self.original[0x14EED40:0x14EED80], self.patched[0x14EED40:0x14EED80])
        self.assertEqual(self.original[WARPED:WARPED + 16], self.patched[WARPED:WARPED + 16])
        self.assertEqual(self.original[SIMPLE:SIMPLE + 16], self.patched[SIMPLE:SIMPLE + 16])
        self.assertEqual(patch_overlay_color(self.patched, restore=True), self.original)
        report = verify_overlay_color(self.patched)
        self.assertEqual(report['external_override_calls_removed'], 2)
        self.assertTrue(report['software_srgb_decode'])
        self.assertTrue(report['composition_oes_decode'])
        self.assertTrue(report['sampler2d_decode'])
        self.assertFalse(report['sampler2d_sample_unchanged'])
        self.assertEqual(report['patch_bytes'], LENGTH + SITE * 2 + 12 + len(COMPOSITION_IMAGE) + len(S2_IMAGE))
        with self.assertRaises(ValueError):
            patch_overlay_color(self.patched)
        with self.assertRaises(ValueError):
            patch_overlay_color(self.original, restore=True)

    def test_composition_cave_decodes_external_layers_only(self):
        self.assertEqual(self.original[SAMPLE:SAMPLE + len(SAMPLE_BYTES)], SAMPLE_BYTES)
        self.assertTrue(all(byte == 0 for byte in self.original[CAVE:CAVE + len(COMPOSITION_IMAGE)]))
        self.assertEqual(self.patched[CAVE:CAVE + len(COMPOSITION_IMAGE)], COMPOSITION_IMAGE)
        self.assertTrue(COMPOSITION_IMAGE.endswith(DECODE))
        code = _disasm(self.patched, CAVE, 4 * (TYPE2_LEN + TYPE3_LEN))
        self.assertEqual(_resolved_adds(code), [WARPED, SIMPLE, STRING, STRING])
        calls = [insn.op_str for insn in code if insn.mnemonic == 'bl']
        self.assertEqual(calls, [f'#{DECRYPT:#x}', f'#{FORMAT:#x}', f'#{APPEND:#x}', f'#{APPEND:#x}',
                                 f'#{APPEND:#x}', f'#{APPEND:#x}'])
        jumps = [insn for insn in code if insn.mnemonic == 'b']
        self.assertEqual([(insn.address, insn.op_str) for insn in jumps], [(CAVE + 4 * (TYPE2_LEN - 1), f'#{RESUME:#x}')])
        type2 = _disasm(self.patched, TYPE2_BRANCH, 4)
        type3 = _disasm(self.patched, TYPE3_CALL, 4)
        self.assertEqual((type2[0].mnemonic, type2[0].op_str), ('b', f'#{CAVE:#x}'))
        self.assertEqual((type3[0].mnemonic, type3[0].op_str), ('bl', f'#{TYPE3_CAVE:#x}'))
        trampoline = code[TYPE2_LEN:]
        self.assertEqual([(insn.mnemonic, insn.op_str) for insn in trampoline[:3]], [
            ('sub', 'sp, sp, #0x10'),
            ('str', 'x30, [sp]'),
            ('bl', f'#{APPEND:#x}'),
        ])
        self.assertEqual([(insn.mnemonic, insn.op_str) for insn in trampoline[-3:]], [
            ('ldr', 'x30, [sp]'),
            ('add', 'sp, sp, #0x10'),
            ('ret', ''),
        ])
        self.assertEqual(trampoline[3].op_str, 'x8, [sp, #0x58]')
        self.assertEqual(trampoline[4].op_str, 'x9, [sp, #0x88]')
        self.assertIn(b'color_top.rgb', self.patched[STRING:STRING + len(DECODE)])
        self.assertEqual(self.patched[S2_CAVE:S2_CAVE + len(S2_IMAGE)], S2_IMAGE)
        self.assertTrue(all(byte == 0 for byte in self.original[S2_CAVE:S2_CAVE + len(S2_IMAGE)]))
        s2 = _disasm(self.patched, S2_CAVE, 4 * S2_LEN)
        self.assertEqual(_resolved_adds(s2), [WARPED, SIMPLE, STRING])
        s2_calls = [insn.op_str for insn in s2 if insn.mnemonic == 'bl']
        self.assertEqual(s2_calls, [f'#{DECRYPT:#x}', f'#{FORMAT:#x}', f'#{APPEND:#x}', f'#{APPEND:#x}'])
        s2_jumps = [insn for insn in s2 if insn.mnemonic == 'b']
        self.assertEqual([(insn.address, insn.op_str) for insn in s2_jumps],
                         [(S2_CAVE + 4 * (S2_LEN - 1), f'#{RESUME:#x}')])
        taken = _disasm(self.patched, SAMPLE, 4)
        self.assertEqual((taken[0].mnemonic, taken[0].op_str), ('b', f'#{S2_CAVE:#x}'))

    def test_composes_with_the_display_mode_patch(self):
        mode = patch_display_mode(self.original)
        both = patch_overlay_color(mode)
        self.assertEqual(patch_overlay_color(both, restore=True), mode)
        self.assertEqual(patch_display_mode(patch_overlay_color(both, restore=True), restore=True), self.original)
        self.assertEqual(verify_overlay_color(both)['sampler_names_preserved'], True)
        self.assertEqual(verify_overlay_color(both)['composition_oes_decode'], True)


if __name__ == '__main__':
    unittest.main()
