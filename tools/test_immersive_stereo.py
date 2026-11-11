"""Execute immersive switching and geometry, and compile every changed GLSL stage."""
import ctypes
import hashlib
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

from app_color import LIBRARY
from build_embedded_adb import BASE, ROOT
from immersive_stereo import (CODE_ADDRESS, MODES, SHADERS, TOP_BOTTOM_UV,
                              assemble_native, native_spec, native_targets,
                              patch_immersive_shader, patch_immersive_stereo,
                              program_source, shader_records, verify_immersive_shaders)
from package_identity import patch_binary_identity
from recording_logo import patch_recording_logo
from test_recording_logo import PRIOR_PATCHES
from xreal_telemetry import file_offset, verify_library

sys.path.insert(0, str(ROOT / "artifacts/tools/python"))
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn import arm64_const as arm

STOP = 0x5800000
MENU, PREF, VIEW, OVERLAY = 0x6000000, 0x6001000, 0x6002000, 0x6003000
CURRENT, NEXT, CLASS = 0x6004000, 0x6005000, 0x6006000


class Machine:
    def __init__(self, blob):
        self.cpu = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        self.cpu.mem_map(0, 0x7000000)
        phoff = struct.unpack_from("<Q", blob, 32)[0]
        stride, count = struct.unpack_from("<HH", blob, 54)
        for i in range(count):
            kind, flags, at, va, _, length = struct.unpack_from("<IIQQQQ", blob, phoff + i * stride)
            if kind != 1:
                continue
            if not flags & 1:
                self.cpu.mem_write(va, blob[at:at + length])
        for start, size in ((0x12CF000, 0x2000), (0x33D7000, 0x8000), (0x1C4D000, 0x1000), (0x1279000, 0x2000),
                            (0x34D9000, 0x1D000), (0x3574000, 0x1000), (0x35E9000, 0x1000)):
            at = file_offset(blob, start)
            self.cpu.mem_write(start, blob[at:at + size])
        self.heap = 0x6100000
        self.strings = {}
        self.pending_strings = {}
        self.text = {CURRENT: 0, NEXT: 0}
        self.changes = []
        self.rects = []
        self.now = 10.0
        self.write(MENU + 0x2b0, PREF)
        self.write(MENU + 0x110, CURRENT)
        self.write(MENU + 0x108, NEXT)
        self.write(MENU + 0x298, 0x6007000)
        self.write(VIEW + 0x200, MENU)
        self.write(VIEW + 0x190, OVERLAY)
        self.write(CLASS + 0x548, 0x5700000)
        self.write(CLASS + 0x558, 0x5700004)
        for target in (CURRENT, NEXT):
            self.write(target, CLASS)
        for got, label in ((0x45E4A68, "Half SBS"), (0x45875C8, "Full SBS")):
            self.write(self.read(got), self.string(label))
        self.set("sp", 0x6FFF000)
        self.cpu.hook_add(UC_HOOK_CODE, self.hook)

    def get(self, name):
        return self.cpu.reg_read(getattr(arm, "UC_ARM64_REG_" + name.upper()))

    def set(self, name, value):
        self.cpu.reg_write(getattr(arm, "UC_ARM64_REG_" + name.upper()), value)

    def write(self, address, value, fmt="<Q"):
        self.cpu.mem_write(address, struct.pack(fmt, value))

    def read(self, address, fmt="<Q"):
        return struct.unpack(fmt, self.cpu.mem_read(address, struct.calcsize(fmt)))[0]

    def string(self, value):
        at = self.heap
        self.heap += 0x100
        self.strings[at] = value
        return at

    def run(self, start, end=STOP):
        self.set("x30", end)
        self.cpu.emu_start(start, end, count=10000)
        if self.get("pc") != end:
            raise AssertionError(f"Native execution did not finish: {self.get('pc'):#x}")

    def returned(self, value=0):
        self.set("x0", value)
        self.set("pc", self.get("x30"))

    def hook(self, cpu, address, size, data):
        if address == 0xD601E0:  # Initialize a managed metadata slot.
            slot = self.get("x0")
            if slot in self.pending_strings:
                self.write(slot, self.string(self.pending_strings.pop(slot)))
            elif self.read(slot) not in self.strings and self.read(slot) != CLASS:
                self.write(slot, CLASS)
            self.write(CLASS + 0xE0, 1, "<I")
            self.returned()
        elif address in (0xD602C0, 0x346AA44, 0x1A2F0E4):
            self.returned()
        elif address == 0xD602D4:
            self.returned(0x6008000)
        elif address == 0x324D86C:
            self.set("s0", struct.unpack("<I", struct.pack("<f", self.now))[0])
            self.set("pc", self.get("x30"))
        elif address == 0x336B54C:
            action = 0x6009000
            self.write(action + 0x18, 0x34E2884)
            self.write(action + 0x40, VIEW)
            self.write(self.get("x2") + 8, action)
            self.returned(1)
        elif address == 0x34E0ED4:
            mode = self.get("w1")
            self.changes.append((mode, self.get("w2"), self.get("w3"), self.get("w4")))
            self.write(PREF + 0x30, mode, "<I")
            self.write(PREF + 0x34, mode, "<I")
            self.write(VIEW + 0x1dc, mode, "<I")
            self.returned()
        elif address in (0x32B8CA8, 0x1EF9D18):
            self.returned(0x600A000)
        elif address == 0x1C7C844:
            # Complete the actual text-scroll callback on the same managed owner.
            self.set("x0", MENU)
            self.set("pc", 0x33DE60C)
        elif address == 0x5700000:
            self.returned(self.text[self.get("x0")])
        elif address == 0x5700004:
            self.text[self.get("x0")] = self.get("x1")
            self.returned()
        elif address == 0x15BBC64:
            if self.get("x0") not in self.strings:
                raise AssertionError("Localization received an unresolved string literal")
            self.returned(self.get("x0"))
        elif address == 0xE04D30:
            at = self.get("x0")
            value = bytes(self.cpu.mem_read(at, 100)).split(b"\0", 1)[0].decode()
            self.returned(self.string(value))
        elif address == 0x32503B8:
            self.returned()
        elif address in (0x3471878, 0x34862E4):
            present = bool(self.get("x0"))
            self.returned(int(not present if address == 0x3471878 else present))
        elif address == 0x2DFBF10:
            for i in range(4):
                self.write(self.get("x0") + i * 4, self.get(f"s{i}"), "<I")
            self.returned()
        elif address == 0x269BA94:
            self.rects.append(struct.unpack("<8f", cpu.mem_read(self.get("x0") + 0x60, 32)))
            self.returned()


PARENT, CLOSE_GAME, CLOSE_TRANSFORM, CLOSE_ITEM = 0x6010000, 0x6010100, 0x6010200, 0x6010300
TB_GAME, TB_TRANSFORM, TB_ITEM, TB_LAYOUT, TB_TEXT_TRANSFORM, TB_TEXT = [0x6010400 + n * 0x100 for n in range(6)]
METHOD, ACTION = 0x6011000, 0x6012000


class PickerMachine(Machine):
    """Unity hierarchy services are fixtures; delegate construction is real ARM64."""
    def __init__(self, blob):
        super().__init__(blob)
        self.created = False
        self.clone_count = 0
        self.children = ["Content", "SelectBtn", "SwitchBtn", "Close", "Arrow"]
        self.visible = False
        self.enabled = False
        self.height = 0
        self.autosize = False
        self.font_min = 0
        self.original_heights = [48, 0, 0]
        self.write(MENU + 0xC0, CLOSE_ITEM)
        self.write(MENU + 0x288, 1, "<B")
        self.write(TB_TEXT, CLASS)
        self.text[TB_TEXT] = self.string("Close")
        self.write(self.read(0x45CB4A8), METHOD)
        self.write(self.read(0x4570740), CLASS)

    def hook(self, cpu, address, size, data):
        # Keep the original layout code isolated from the injected wrappers;
        # these services model its documented expanded/collapsed dimensions.
        if address in (0x33D7F08, 0x33DD10C, 0x33DD4E4, 0x33DA6A8, 0x33DBB94):
            amount = 0x60 if address in (0x33DA6A8, 0x33DBB94) else 0x30
            self.set("sp", self.get("sp") + amount)
            if address == 0x33DD10C:
                self.write(MENU + 0x2AC, 1, "<B")
                self.original_heights = [72, 72, 72] if self.read(PREF + 0x30, "<I") else [0, 0, 48]
            elif address == 0x33DD4E4:
                self.write(MENU + 0x2AC, 0, "<B")
                if self.read(PREF + 0x30, "<I"):
                    self.original_heights = [48, 0, 0]
            elif address == 0x33DA6A8:
                self.write(MENU + 0x288, self.get("w1"), "<B")
            elif address == 0x33DBB94:
                self.original_heights = [48, 0, 0] if self.read(PREF + 0x30, "<I") else [0, 0, 48]
            self.returned()
        elif address == 0x34E0ED4:
            pass  # Execute the saved-mode migration before the stock entry.
        elif address == 0x34E0ED8:
            assert self.get("x0") == VIEW
            self.set("sp", self.get("sp") + 0x70)
            super().hook(cpu, 0x34E0ED4, size, data)
        elif address == 0x324A770:
            self.returned(CLOSE_TRANSFORM if self.get("x0") == CLOSE_ITEM else TB_TRANSFORM)
        elif address == 0x324E6B8:
            self.returned(PARENT)
        elif address == 0x324A7AC:
            self.returned(CLOSE_GAME if self.get("x0") in (CLOSE_ITEM, CLOSE_TRANSFORM) else TB_GAME)
        elif address == 0x324FB48:
            parent, name = self.get("x0"), self.strings[self.get("x1")]
            result = (TB_TRANSFORM if self.created else 0) if parent == PARENT else TB_TEXT_TRANSFORM
            if parent == PARENT and name != "UnboundHalfTopBottom":
                raise AssertionError(name)
            if parent == TB_TRANSFORM and name != "stateTxt":
                raise AssertionError(name)
            self.returned(result)
        elif address == 0x3485748:
            assert (self.get("x0"), self.get("x1"), self.get("w2")) == (CLOSE_GAME, PARENT, 0)
            self.clone_count += 1
            self.created = True
            self.children.append("Close(Clone)")
            self.returned(TB_GAME)
        elif address == 0x3484FF4:
            assert self.get("x0") == TB_GAME
            self.children[-1] = self.strings[self.get("x1")]
            self.returned()
        elif address == 0x3486C6C:
            self.returned(TB_TRANSFORM)
        elif address == 0x324FA74:
            assert self.get("x0") == TB_TRANSFORM
            value = self.children.pop()
            self.children.insert(self.get("w1"), value)
            self.returned()
        elif address == 0xD601E0 and self.get("x0") in (0x4818640, 0x4818900, 0x4818B48):
            slot = self.get("x0")
            self.write(slot, slot + 0x1000000)
            self.returned()
        elif address == 0x256E4F0:
            self.returned({0x5818640: TB_ITEM, 0x5818900: TB_LAYOUT, 0x5818B48: TB_TEXT}[self.get("x1")])
        elif address == 0x3486D28:
            assert self.get("x0") == TB_GAME
            self.visible = bool(self.get("w1"))
            self.returned()
        elif address == 0x1DC2B04:
            self.height = struct.unpack("<f", struct.pack("<I", self.get("s0")))[0]
            self.returned()
        elif address == 0x32B7DA8:
            self.autosize = bool(self.get("w1"))
            self.returned()
        elif address == 0x32B7DFC:
            self.font_min = struct.unpack("<f", struct.pack("<I", self.get("s0")))[0]
            self.returned()
        elif address == 0xD602D4:
            self.write(ACTION, CLASS)
            self.returned(ACTION)
        elif address == 0xD602B8:
            assert self.get("x0") == METHOD
            self.returned(0x33DCE80)
        elif address == 0xD60244:
            self.returned(0)  # The original MethodInfo is a void instance method.
        elif address == 0x1E6727C:
            assert self.get("x0") == 0  # Fresh clone: no inherited runtime event.
            self.returned(self.get("x1"))
        elif address == 0xDA3164:
            at, value, expected = self.get("x0"), self.get("x1"), self.get("x2")
            old = self.read(at)
            if old == expected:
                self.write(at, value)
            self.returned(old)
        elif address == 0x33DCFA8:
            assert self.get("x1") == TB_ITEM
            self.enabled = bool(self.get("w2"))
            self.returned()
        else:
            super().hook(cpu, address, size, data)

    def invoke(self, entry):
        self.set("x0", MENU)
        self.run(entry)
        assert self.get("sp") == 0x6FFF000

    def click_top_bottom(self):
        action = self.read(TB_ITEM + 0x28)
        self.set("x0", self.read(action + 0x40))
        self.set("x1", self.read(action + 0x28))
        self.run(self.read(action + 0x18))


class ImmersiveStereoTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(BASE) as archive:
            cls.original = archive.read(LIBRARY)
            cls.shaders = {name: archive.read(name) for name in SHADERS}
        for patch in (*PRIOR_PATCHES, patch_recording_logo):
            cls.original = patch(cls.original)
        cls.patched = patch_immersive_stereo(cls.original)

    def test_picker_adds_a_separate_third_choice_without_replacing_close(self):
        m = PickerMachine(self.patched)
        m.invoke(0x33D7F04)
        self.assertEqual(m.children, ["Content", "SelectBtn", "SwitchBtn", "UnboundHalfTopBottom", "Close", "Arrow"])
        self.assertEqual(m.strings[m.text[TB_TEXT]], "Half Top/Bottom")
        self.assertEqual((m.height, m.autosize, m.font_min), (72, True, 18))
        self.assertFalse(m.visible)
        self.assertEqual(m.read(ACTION + 0x20), MENU)
        self.assertEqual(m.read(ACTION + 0x28), METHOD)
        self.assertEqual(m.read(ACTION + 0x10), m.read(ACTION + 0x18))
        m.invoke(0x33D7F04)
        self.assertEqual(m.clone_count, 1)

    def test_cold_app_window_initializes_labels_before_localization(self):
        for mode in range(5):
            with self.subTest(mode=mode):
                m = PickerMachine(self.patched)
                # These are encoded metadata tokens on a fresh renderer. The
                # stock Start method does not initialize the hover-only labels.
                for got, token, label in ((0x45E4A68, 0xA000B005, "Half SBS"),
                                          (0x45875C8, 0xA000B003, "Full SBS")):
                    slot = m.read(got)
                    m.write(slot, token)
                    m.pending_strings[slot] = label
                m.write(PREF + 0x30, mode, "<I")
                m.invoke(0x33D7F04)
                m.invoke(0x33DBB90)
                expected = "Half Top/Bottom" if mode in (3, 4) else "Full SBS" if mode == 2 else "Half SBS"
                self.assertEqual(m.strings[m.text[CURRENT]], expected)
                self.assertEqual(m.strings[m.text[NEXT]], "Full SBS")
                self.assertEqual(m.strings[m.text[TB_TEXT]], "Half Top/Bottom")

    def test_all_three_choices_are_visible_and_direct_from_every_format(self):
        m = PickerMachine(self.patched)
        m.invoke(0x33D7F04)
        for current in MODES:
            for chosen in MODES:
                m.write(PREF + 0x30, current, "<I")
                m.invoke(0x33DD108)
                self.assertEqual(m.original_heights, [72, 72, 72])
                self.assertTrue(m.visible and m.enabled)
                self.assertEqual([m.strings[m.text[t]] for t in (CURRENT, NEXT, TB_TEXT)], list(MODES.values()))
                before = len(m.changes)
                if chosen == 3:
                    m.click_top_bottom()
                else:
                    m.invoke(0x33DCE80 if chosen == 1 else 0x33DD7A0)
                self.assertEqual(len(m.changes), before + int(current != chosen))
                if current != chosen:
                    self.assertEqual(m.changes[-1], (chosen, 1, 0, 1))
                m.invoke(0x33DBB90)
                self.assertTrue(m.visible and m.enabled)
                self.assertEqual(m.original_heights, [72, 72, 72])
                m.invoke(0x33DD4E0)
                self.assertFalse(m.visible or m.enabled)
                self.assertEqual(m.strings[m.text[CURRENT]], MODES[chosen])

    def test_added_choice_follows_menu_interactability(self):
        m = PickerMachine(self.patched)
        m.invoke(0x33D7F04)
        m.write(PREF + 0x30, 1, "<I")
        m.invoke(0x33DD108)
        for enabled in (0, 1, 0, 1):
            m.set("w1", enabled)
            m.invoke(0x33DA6A4)
            self.assertEqual(m.enabled, bool(enabled))
            self.assertTrue(m.visible)

    def test_turning_3d_off_collapses_the_picker_to_the_stock_off_state(self):
        m = PickerMachine(self.patched)
        m.invoke(0x33D7F04)
        m.invoke(0x33DD108)
        self.assertFalse(m.visible)
        self.assertEqual(m.original_heights, [0, 0, 48])
        m.invoke(0x33DCE80)
        m.invoke(0x33DBB90)
        self.assertEqual(m.original_heights, [72, 72, 72])
        self.assertTrue(m.visible)
        m.write(PREF + 0x30, 0, "<I")
        m.invoke(0x33DBB90)
        self.assertFalse(m.visible)
        self.assertEqual(m.original_heights, [0, 0, 48])
        m.invoke(0x33DD4E0)
        self.assertFalse(m.visible)
        self.assertEqual(m.original_heights, [0, 0, 48])

    def test_saved_removed_format_migrates_to_half_top_bottom(self):
        m = PickerMachine(self.patched)
        for old, expected in ((0, 0), (1, 1), (2, 2), (3, 3), (4, 3)):
            m.set("w1", old)
            m.set("x0", VIEW)
            m.run(0x34E0ED4)
            self.assertEqual(m.changes[-1][0], expected)
        m.write(PREF + 0x30, 0, "<I")
        m.write(PREF + 0x34, 4, "<I")
        m.invoke(0x33DBB90)
        self.assertEqual(m.strings[m.text[CURRENT]], "Half Top/Bottom")

    def test_only_full_sbs_changes_buffer_dimensions(self):
        m = Machine(self.patched)
        for mode in range(4):
            m.write(0x600B064, 1920, "<I")
            m.write(0x600B068, 1080, "<I")
            m.set("x8", 0x600B000)
            m.set("x22", 0x600B100)
            m.set("w26", mode)
            m.run(0x34F51C0, 0xD6022C)
            self.assertEqual((m.get("w22"), m.get("w21")), (3840 if mode == 2 else 1920, 1080))

    def test_close_handler_is_preserved_and_full_top_bottom_has_no_picker_label(self):
        at = file_offset(self.original, 0x33DD9AC)
        self.assertEqual(self.original[at:at + 0x11C], self.patched[at:at + 0x11C])
        self.assertNotIn(b"Full Top/Bottom", assemble_native()[0])
        for address in (0x33DD1A0, 0x33DD550):
            at = file_offset(self.patched, address)
            self.assertEqual(self.patched[at:at + 4], self.original[at:at + 4])





    def test_both_overlay_updates_select_top_for_left_eye_and_preserve_mono_sbs(self):
        m = Machine(self.patched)
        for mode in range(4):
            for start in (0x34E1684, 0x34E3394):
                with self.subTest(mode=mode, entry=hex(start)):
                    m.set("x0", VIEW)
                    m.set("w1", mode)
                    m.write(VIEW + 0x1DC, mode, "<I")
                    m.run(start)
                    expected = ((0, .5, 1, .5, 0, 0, 1, .5) if mode >= 3 else
                                ((0, 0, .5, 1, .5, 0, .5, 1) if mode else (0, 0, 1, 1) * 2))
                    self.assertEqual(m.rects[-1], expected)
                    self.assertEqual(m.read(OVERLAY + 0x5C, "<B"), int(mode != 0))
                    self.assertEqual(m.get("sp"), 0x6FFF000)

    def test_input_preview_restores_the_last_full_format_and_current_half_format(self):
        m = Machine(self.patched)
        for mode in (1, 2, 3):
            m.set("w8", mode if mode in (1, 3) else 0)
            m.set("x19", VIEW)
            m.write(PREF + 0x34, mode, "<I")
            m.run(0x34E6F2C, 0x34E6F38)
            self.assertEqual(m.get("w1"), mode)
        for mode in (1, 2, 3):
            m.set("w8", mode)
            m.run(0x34E09D8, 0x34E09DC)
            self.assertEqual(bool(m.get("nzcv") & (1 << 30)), mode == 2)


    def test_reopening_a_full_top_bottom_window_restores_mono_height(self):
        m = Machine(self.patched)
        for start, end, owner in ((0x34EE33C, 0x34EE340, "x22"), (0x34EE618, 0x34EE61C, "x23")):
            for mode in range(5):
                m.set(owner, PREF)
                m.set("w9", mode)
                m.write(PREF + 0x14, 2160, "<I")
                m.run(start, end)
                self.assertEqual(m.read(PREF + 0x14, "<I"), 1080 if mode == 4 else 2160)

    def test_exact_native_scope_and_existing_patch_composition(self):
        self.assertEqual(patch_immersive_stereo(self.patched, restore=True), self.original)
        self.assertEqual(verify_library(self.patched), 40)
        moved = patch_binary_identity(LIBRARY, self.patched)
        self.assertEqual(patch_binary_identity(LIBRARY, moved, restore=True), self.patched)
        restored = bytearray(self.patched)
        for _, address, before, _ in native_targets():
            at = file_offset(restored, address)
            restored[at:at + len(before)] = before
        self.assertEqual(restored, self.original)
        for blob, restore in ((self.patched, False), (self.original, True)):
            with self.assertRaises(ValueError):
                patch_immersive_stereo(blob, restore=restore)
        damaged = bytearray(self.patched)
        damaged[-1] ^= 1
        with self.assertRaises(ValueError):
            patch_immersive_stereo(damaged, restore=True)

    def test_shader_records_keep_vertex_stages_metadata_and_unchanged_parameters(self):
        for name, original in self.shaders.items():
            patched = patch_immersive_shader(original)
            *_, before, entries = shader_records(original)
            *_, after, new_entries = shader_records(patched)
            for (at, length, _), (new_at, new_length, _) in zip(entries, new_entries):
                a, b = before[at:at + length], after[new_at:new_at + new_length]
                old_source, new_source = program_source(a), program_source(b)
                if old_source is None:
                    self.assertEqual(a, b)
                    continue
                self.assertEqual(old_source[2], new_source[2])
                self.assertEqual(a[:old_source[0] - 4], b[:new_source[0] - 4])
                self.assertEqual(old_source[1].split(b"#ifdef FRAGMENT")[0],
                                 new_source[1].split(b"#ifdef FRAGMENT")[0])
                self.assertIn(TOP_BOTTOM_UV, new_source[1])
        with zipfile.ZipFile(BASE) as base:
            class Candidate:
                def read(_, name):
                    return patch_immersive_shader(self.shaders[name]) if name in SHADERS else base.read(name)
            report = verify_immersive_shaders(Candidate(), base)
        self.assertEqual(sum(report["unity_native_programs_loaded"].values()), 160)

    def test_all_changed_glsl_stages_compile(self):
        checked = set()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            compiler = root / "glslangValidator"
            shutil.copyfile(Path.home() / "Library/Android/sdk/emulator/lib64/vulkan/glslangValidator", compiler)
            compiler.chmod(0o755)
            for name, raw in self.shaders.items():
                *_, blob, entries = shader_records(patch_immersive_shader(raw))
                for at, length, _ in entries:
                    source = program_source(blob[at:at + length])
                    if source is None:
                        continue
                    for stage, marker in (("vert", b"#ifdef VERTEX"), ("frag", b"#ifdef FRAGMENT")):
                        code = source[1].split(marker, 1)[1]
                        if stage == "vert":
                            code = code.split(b"#ifdef FRAGMENT", 1)[0]
                        code = code[:code.rfind(b"#endif")].strip(b"\0\n ")
                        # Select the shader's existing GLES 3.0 uniform lookup
                        # branch; this host compiler lacks Unity's location extension.
                        code = code.replace(b"#define UNITY_SUPPORTS_UNIFORM_LOCATION 1",
                                            b"#define UNITY_SUPPORTS_UNIFORM_LOCATION 0")
                        digest = hashlib.sha256(code).digest()
                        if digest in checked:
                            continue
                        checked.add(digest)
                        path = root / (str(len(checked)) + "." + stage)
                        path.write_bytes(code)
                        result = subprocess.run([str(compiler), "-S", stage, str(path)], capture_output=True, text=True)
                        self.assertEqual(result.returncode, 0, name + "\n" + result.stdout + result.stderr)
        self.assertGreater(len(checked), 20)

    def test_actual_uv_function_samples_each_eye_without_changing_mono_or_sbs(self):
        # Execute the exact added GLSL function with a minimal C++ vec2 type.
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "uv.cpp"
            source.write_text("struct vec2 { float x,y; vec2(float a,float b):x(a),y(b){} }; int _StereoMode;\n"
                              + TOP_BOTTOM_UV.decode() + '\nextern "C" void sample(int mode,float x,float y,bool left,float* out)'
                              '{_StereoMode=mode;vec2 v=unboundTopBottomUV(vec2(x,y),left);out[0]=v.x;out[1]=v.y;}')
            library = root / "uv.dylib"
            subprocess.run(["clang++", "-dynamiclib", str(source), "-o", str(library)], check=True)
            sample = ctypes.CDLL(str(library)).sample
            sample.argtypes = [ctypes.c_int, ctypes.c_float, ctypes.c_float, ctypes.c_bool,
                               ctypes.POINTER(ctypes.c_float)]
            result = (ctypes.c_float * 2)()
            for mode in range(5):
                for left in (False, True):
                    for x, y in ((0, 0), (.25, .25), (.5, .5), (.75, .75), (1, 1)):
                        sample(mode, x, y, left, result)
                        self.assertEqual(result[0], x)
                        self.assertEqual(result[1], y / 2 + (.5 if left else 0) if mode == 3 else y)


if __name__ == "__main__":
    unittest.main()
