"""Decode protected external overlays when Mali rejects the sRGB override.

XREAL asks GL_TEXTURE_FORMAT_SRGB_OVERRIDE_EXT to sample external app images
as sRGB. Mali returns GL_INVALID_ENUM. Encoded pixels then sit in a surface
that encodes again, and midtones wash out.

The static samplerExternalOES shader is the blit shader. It is rewritten to
the IEC curve, and both external override calls are removed so a GPU that
implements the extension does not decode twice.

YouTube is drawn by RenderCompositionOpenGL::GetComposeShader, which appends
a pass-through sample for each layer. That sample text is shared, so the text
stays. Builds 2922 and 2923 decoded the blit shader and external layers
(type 2, and type 3 with a texel size). On the Pixel those layers are not the
picture: YouTube is one virtual-display RGBA window, sampled as sampler2D.
The first instruction of the shared sample now branches to a cave that
repeats the sample and appends an IEC decode of color_top.rgb. Type 2 still
has its own copy of the sample, so it does not enter this cave. sampler2DArray,
warp passes, and protected-texture setup are unchanged.
"""
import struct

from xreal_telemetry import file_offset

LIBRARY = 'lib/arm64-v8a/libnr_api.so'
SHADER = 0x6911DD
LENGTH = 318
SITES = (0x14EEC2C, 0x14EF1F4)
SITE = 16
NOP = bytes.fromhex('1f2003d5') * 4
ORIGINAL_SITES = {
    0x14EEC2C: bytes.fromhex('a0ac9152e1f791520288915292222f94'),
    0x14EF1F4: bytes.fromhex('a0ac9152e1f791520288915220212f94'),
}
ORIGINAL = (
    b'#version 300 es\n'
    b' #extension GL_OES_EGL_image_external_essl3: require\n'
    b' precision mediump float;\n'
    b' precision lowp samplerExternalOES;\n'
    b' uniform samplerExternalOES srcTex;\n'
    b' in vec2 vTexcoord0;\n'
    b' out vec4 outColor;\n'
    b' void main()\n'
    b' {\n'
    b'  vec4 srcColor = texture(srcTex, vTexcoord0);\n'
    b'  outColor = vec4(srcColor.xyz,srcColor.a);\n'
    b' }\n'
)
REPLACEMENT = (
    b'#version 300 es\n'
    b'#extension GL_OES_EGL_image_external_essl3: require\n'
    b'precision highp float;'
    b'uniform highp samplerExternalOES srcTex;'
    b'in vec2 vTexcoord0;'
    b'out vec4 o;'
    b'void main(){'
    b'vec4 s=texture(srcTex,vTexcoord0);'
    b'vec3 c=s.rgb;'
    b'c=mix(pow((c+.055)/1.055,vec3(2.4)),c/12.92,lessThanEqual(c,vec3(.04045)));'
    b'o=vec4(c,s.a);}'
).ljust(LENGTH, b' ')

# Composition generator. The type-2 branch is the only jump to the shared sample.
SAMPLE = 0x14D6A20
RESUME = 0x14D6A70
TYPE2_BRANCH = 0x14D692C
TYPE3_CALL = 0x14D6BC0
APPEND = 0x1DD77F4
DECRYPT = 0x1E56C08
FORMAT = 0x14D8AE8
WARPED = 0x3ED3BC
SIMPLE = 0x5CB03B
CAVE = 0x7547EC
CAVE_LIMIT = 0x754BF0  # libc++ RTTI begins here; the gap before it is zeros.
DECODE = (
    b'\tcolor_top.rgb=mix(pow((color_top.rgb+vec3(.055))/vec3(1.055),vec3(2.4)),'
    b'color_top.rgb/vec3(12.92),lessThanEqual(color_top.rgb,vec3(.04045)));\n\x00'
)


def branch_word(pc, target, link=False):
    if (pc | target) & 3:
        raise ValueError(f'unaligned branch {pc:#x} -> {target:#x}')
    offset = (target - pc) >> 2
    if offset < -0x2000000 or offset >= 0x2000000:
        raise ValueError(f'branch {pc:#x} -> {target:#x} is out of range')
    base = 0x94000000 if link else 0x14000000
    return base | (offset & 0x3FFFFFF)


def adrp_word(rd, pc, target):
    imm = ((target & ~0xFFF) - (pc & ~0xFFF)) >> 12
    if imm < -0x100000 or imm >= 0x100000:
        raise ValueError(f'ADRP {pc:#x} -> {target:#x} is out of range')
    imm &= 0x1FFFFF
    return 0x90000000 | ((imm & 3) << 29) | ((imm >> 2) << 5) | rd


def add_word(rd, rn, imm12):
    if not 0 <= imm12 <= 0xFFF:
        raise ValueError(f'ADD immediate {imm12:#x} does not fit')
    return 0x91000000 | (imm12 << 10) | (rn << 5) | rd


def ldrsw_sp(rt, byte_offset):
    if byte_offset & 3 or not 0 <= byte_offset <= 0x3FFC:
        raise ValueError(f'LDRSW offset {byte_offset:#x} is not encodable')
    return 0xB9800000 | ((byte_offset // 4) << 10) | (31 << 5) | rt


def ldr_sp(rt, byte_offset):
    if byte_offset & 7 or not 0 <= byte_offset <= 0x7FF8:
        raise ValueError(f'LDR offset {byte_offset:#x} is not encodable')
    return 0xF9400000 | ((byte_offset // 8) << 10) | (31 << 5) | rt


def _pack(words):
    return b''.join(struct.pack('<I', word) for word in words)


def sample_words(pc):
    """The shared sample at SAMPLE, with ADRP and BL retargeted to pc.

    Type 2 and sampler2D each repeat this block in their own cave. The bytes
    after the first instruction stay in place and are not executed.
    """
    specs = (
        ('word', 0x3942B288),          # ldrb w8, [x20, #0xac]
        ('adrp', 9, WARPED),
        ('word', 0x910EF129),          # add x9, x9, #0x3bc
        ('word', 0x7100011F),          # cmp w8, #0
        ('adrp', 8, SIMPLE),
        ('word', 0x9100ED08),          # add x8, x8, #0x3b
        ('word', 0x9A880120),          # csel x0, x9, x8, eq
        ('bl', DECRYPT),
        ('word', 0xB9404BE3),          # ldr w3, [sp, #0x48]
        ('word', 0xAA0003E2),          # mov x2, x0
        ('word', 0x9104C3E0),          # add x0, sp, #0x130
        ('word', 0x52804001),          # mov w1, #0x200
        ('word', 0x2A0303E4),          # mov w4, w3
        ('bl', FORMAT),
        ('word', 0xB9804BE8),          # ldrsw x8, [sp, #0x48]
        ('word', 0xF9403FE9),          # ldr x9, [sp, #0x78]
        ('word', 0xD503201F),          # nop
        ('word', 0x9B3B2500),          # smaddl x0, w8, w27, x9
        ('word', 0x9104C3E1),          # add x1, sp, #0x130
        ('bl', APPEND),
    )
    words = []
    for index, spec in enumerate(specs):
        here = pc + 4 * index
        kind = spec[0]
        if kind == 'word':
            words.append(spec[1])
        elif kind == 'adrp':
            words.append(adrp_word(spec[1], here, spec[2]))
        elif kind == 'bl':
            words.append(branch_word(here, spec[1], link=True))
        else:
            raise ValueError(kind)
    return words


def decode_append_words(pc, index_offset, base_offset, string):
    """Append the IEC line to the layer string the sample block just wrote."""
    return [
        ldrsw_sp(8, index_offset),
        ldr_sp(9, base_offset),
        0x9B3B2500,
        adrp_word(1, pc + 12, string),
        add_word(1, 1, string & 0xFFF),
        branch_word(pc + 20, APPEND, link=True),
    ]


TYPE2_LEN = 27
TYPE3_LEN = 12
STRING = CAVE + 4 * (TYPE2_LEN + TYPE3_LEN)
TYPE3_CAVE = CAVE + 4 * TYPE2_LEN


def type2_words(string):
    words = sample_words(CAVE)
    words.extend(decode_append_words(CAVE + 4 * len(words), 0x48, 0x78, string))
    words.append(branch_word(CAVE + 4 * len(words), RESUME))
    return words


def type3_words(string):
    # The caller's x0/x1 already point at the type-3 sample. Pushing 16 bytes
    # moves its frame, so the second append reloads index and base at +16.
    pc = TYPE3_CAVE
    words = [
        0xD10043FF,  # sub sp, sp, #16
        0xF90003FE,  # str x30, [sp]
        branch_word(pc + 8, APPEND, link=True),
    ]
    words.extend(decode_append_words(pc + 4 * len(words), 0x58, 0x88, string))
    words.extend((
        0xF94003FE,  # ldr x30, [sp]
        0x910043FF,  # add sp, sp, #16
        0xD65F03C0,  # ret
    ))
    return words


def composition_image():
    words = type2_words(STRING) + type3_words(STRING)
    if (len(type2_words(STRING)), len(type3_words(STRING))) != (TYPE2_LEN, TYPE3_LEN):
        raise RuntimeError('composition cave layout drifted')
    image = _pack(words) + DECODE
    if CAVE + len(image) > CAVE_LIMIT:
        raise RuntimeError('composition cave overflows the zero gap')
    return image


COMPOSITION_IMAGE = composition_image()
S2_CAVE = (CAVE + len(COMPOSITION_IMAGE) + 3) & ~3


def sampler2d_words():
    words = sample_words(S2_CAVE)
    words.extend(decode_append_words(S2_CAVE + 4 * len(words), 0x48, 0x78, STRING))
    words.append(branch_word(S2_CAVE + 4 * len(words), RESUME))
    return words


S2_IMAGE = _pack(sampler2d_words())
S2_LEN = len(sampler2d_words())
SAMPLE_HEAD = 0x3942B288  # ldrb w8, [x20, #0xac]
SAMPLE_BYTES = _pack(sample_words(SAMPLE))
if S2_CAVE + len(S2_IMAGE) > CAVE_LIMIT:
    raise RuntimeError('sampler2D cave overflows the zero gap')

assert len(ORIGINAL) == LENGTH and len(REPLACEMENT) == LENGTH
assert b'srcTex' in REPLACEMENT and b'vTexcoord0' in REPLACEMENT
assert b'GL_OES_EGL_image_external_essl3' in REPLACEMENT
assert REPLACEMENT.count(b'{') == REPLACEMENT.count(b'}') == 1
assert DECODE.count(b'\x00') == 1 and DECODE.endswith(b'\n\x00')
assert len(SAMPLE_BYTES) == RESUME - SAMPLE


def _read(blob, address, size):
    offset = file_offset(blob, address)
    last = file_offset(blob, address + size - 4)
    if last - offset != size - 4:
        raise ValueError(f'patch at {address:#x} crosses a segment')
    return offset, bytes(blob[offset:offset + size])


def _schedule(blob, edits, address, current, new, label):
    offset, found = _read(blob, address, len(current))
    if found != current:
        raise ValueError(f'Unexpected {label} at {address:#x}')
    edits.append((offset, new))


def patch_overlay_color(original, *, restore=False):
    blob = bytearray(original)
    edits = []
    shader_now, shader_new = (REPLACEMENT, ORIGINAL) if restore else (ORIGINAL, REPLACEMENT)
    _schedule(blob, edits, SHADER, shader_now, shader_new, 'external overlay shader')
    for address, before in ORIGINAL_SITES.items():
        now, new = (NOP, before) if restore else (before, NOP)
        _schedule(blob, edits, address, now, new, 'sRGB override call')
    if restore:
        head_now = branch_word(SAMPLE, S2_CAVE)
        head_new = SAMPLE_HEAD
    else:
        head_now = SAMPLE_HEAD
        head_new = branch_word(SAMPLE, S2_CAVE)
    _schedule(blob, edits, SAMPLE, head_now.to_bytes(4, 'little'),
              head_new.to_bytes(4, 'little'), 'sampler2D composition branch')
    tail = SAMPLE_BYTES[4:]
    offset, found = _read(blob, SAMPLE + 4, len(tail))
    if found != tail:
        raise ValueError(f'Unexpected composition sample tail at {SAMPLE + 4:#x}')
    s2_now = S2_IMAGE if restore else bytes(len(S2_IMAGE))
    s2_new = bytes(len(S2_IMAGE)) if restore else S2_IMAGE
    _schedule(blob, edits, S2_CAVE, s2_now, s2_new, 'sampler2D composition cave')
    type2_now = branch_word(TYPE2_BRANCH, CAVE if restore else SAMPLE)
    type2_new = branch_word(TYPE2_BRANCH, SAMPLE if restore else CAVE)
    _schedule(blob, edits, TYPE2_BRANCH, type2_now.to_bytes(4, 'little'),
              type2_new.to_bytes(4, 'little'), 'type-2 composition branch')
    type3_now = branch_word(TYPE3_CALL, TYPE3_CAVE if restore else APPEND, link=True)
    type3_new = branch_word(TYPE3_CALL, APPEND if restore else TYPE3_CAVE, link=True)
    _schedule(blob, edits, TYPE3_CALL, type3_now.to_bytes(4, 'little'),
              type3_new.to_bytes(4, 'little'), 'type-3 composition append')
    cave_now = COMPOSITION_IMAGE if restore else bytes(len(COMPOSITION_IMAGE))
    cave_new = bytes(len(COMPOSITION_IMAGE)) if restore else COMPOSITION_IMAGE
    _schedule(blob, edits, CAVE, cave_now, cave_new, 'composition cave')
    for offset, new in edits:
        blob[offset:offset + len(new)] = new
    return bytes(blob)


def verify_overlay_color(blob):
    patch_overlay_color(blob, restore=True)
    text = REPLACEMENT.decode().strip()
    decode = DECODE.decode().strip()
    return {
        'external_override_calls_removed': len(SITES),
        'software_srgb_decode': 'lessThanEqual' in text and '2.4' in text,
        'sampler_names_preserved': 'srcTex' in text and 'vTexcoord0' in text,
        'protected_texture_setup_preserved': True,
        'composition_oes_decode': 'color_top.rgb' in decode and 'lessThanEqual' in decode,
        'sampler2d_decode': 'color_top.rgb' in decode and 'lessThanEqual' in decode,
        'sampler2d_sample_unchanged': False,
        'patch_bytes': LENGTH + SITE * len(SITES) + 12 + len(COMPOSITION_IMAGE) + len(S2_IMAGE),
        'hardware_validation': 'pending',
    }
