#!/usr/bin/env python3
"""Run the packaged ARM64 filter and production UI in an isolated emulator."""
import argparse
import json
from pathlib import Path
import struct
import subprocess
import tempfile

from experimental_stereo import ROOT, native_spec
from test_experimental_stereo import text_section
from trackpad_taps import branch

TEST_FILE = '/data/local/tmp/unbound-stereo-flag'
TEST_BINARY = '/data/local/tmp/unbound-stereo-check'


def build_check(destination):
    spec = native_spec()
    # Read an encoded input mode from argv[1]. All modified registers, the stack
    # and the original return address are checked by the actual ARM64 process.
    harness = '''.text
_start:
    ldr x1, [sp, #16]
    ldrb w0, [x1]
    sub w0, w0, #65
    mov x1, #456
    mov x2, #789
    mov x8, #999
    mov x9, #321
    mov x10, #654
    mov x19, sp
    .word 0x94000000 | (((RETURN_SITE - (0x1000 + (. - _start))) >> 2) & 0x03ffffff)
    mov x20, x0
    cmp sp, x19
    b.ne failed
    cmp x1, #456
    b.ne failed
    cmp x2, #789
    b.ne failed
    cmp x8, #999
    b.ne failed
    cmp x9, #321
    b.ne failed
    cmp x10, #654
    b.ne failed
    mov x0, x20
    b exit
failed:
    mov x0, #98
exit:
    mov x8, #93
    svc #0
'''.replace('RETURN_SITE', hex(spec['return']))
    with tempfile.TemporaryDirectory() as directory:
        source, obj = Path(directory) / 'check.S', Path(directory) / 'check.o'
        source.write_text(harness)
        subprocess.run(['clang', '-target', 'aarch64-linux-android29', '-c', str(source), '-o', str(obj)], check=True)
        code = text_section(obj)
    elf = bytearray(0x4000)
    elf[:64] = struct.pack('<16sHHIQQQIHHHHHH', b'\x7fELF\x02\x01\x01' + b'\0' * 9,
                          3, 183, 1, 0x1000, 64, 0, 0, 64, 56, 4, 0, 0, 0)
    segments = [(1, 5, 0, 0, 0x2000, 0x1000), (1, 5, 0x2000, spec['wrapper'] & ~4095, 0x1000, 0x1000),
                (1, 5, 0x3000, spec['return'] & ~4095, 0x1000, 0x1000), (0x6474e551, 6, 0, 0, 0, 16)]
    for index, (kind, flags, offset, address, size, align) in enumerate(segments):
        elf[64 + index * 56:64 + (index + 1) * 56] = struct.pack('<IIQQQQQQ', kind, flags, offset, address, address, size, size, align)
    elf[0x1000:0x1000 + len(code)] = code
    path = b'/data/user/0/com.xreal.evapro.nebula/files/unbound-experimental-stereo'
    wrapper = bytes.fromhex(spec['replacement']).replace(path, TEST_FILE.encode() + b'\0' * (len(path) - len(TEST_FILE)))
    at = 0x2000 + (spec['wrapper'] & 4095)
    elf[at:at + len(wrapper)] = wrapper
    at = 0x3000 + (spec['return'] & 4095)
    elf[at:at + 4] = branch(spec['return'], spec['wrapper'])
    destination.write_bytes(elf)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='emulator-5554')
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/experimental-stereo')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'): parser.error('Use an isolated emulator')
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    base = [str(Path.home() / 'Library/Android/sdk/platform-tools/adb'), '-s', args.serial]

    def adb(*command, **kwargs):
        return subprocess.run([*base, *map(str, command)], capture_output=True, timeout=60, **kwargs)

    binary = output / 'native-stereo-check'; build_check(binary)
    subprocess.run([*base, 'install', '-r', '-d', str(output / 'settings-preview.apk')], check=True)
    result = adb('shell', 'am', 'instrument', '-w', 'dev.beampro.settings.preview/dev.beampro.settings.ExperimentalStereoChecks', text=True)
    (output / 'android-ui-checks.log').write_text(result.stdout + result.stderr)
    if result.returncode or 'PASS experimental stereo' not in result.stdout or 'FAIL' in result.stdout:
        raise AssertionError(result.stdout + result.stderr)
    print(result.stdout.strip())
    assert adb('push', binary, TEST_BINARY).returncode == 0
    assert adb('shell', 'chmod', '755', TEST_BINARY).returncode == 0
    cases = []
    try:
        for label, data in [('missing', None), ('enabled', b'\1'), ('disabled', b'\0'),
                            ('invalid', b'\2'), ('empty', b''), ('multiple', b'\1\1')]:
            if data is None: assert adb('shell', 'rm', '-f', TEST_FILE).returncode == 0
            else: assert adb('shell', 'cat > ' + TEST_FILE, input=data).returncode == 0
            for mode in range(-1, 16):
                expected = 3 if data == b'\1' and mode in (1, 5, 10, 11) else mode & 255
                actual = adb('shell', TEST_BINARY, chr(65 + mode)).returncode
                cases.append({'flag': label, 'original': mode, 'expected': expected, 'actual': actual})
                assert actual == expected, cases[-1]
    finally:
        adb('shell', 'rm', '-f', TEST_FILE, TEST_BINARY)
    (output / 'native-runtime-checks.json').write_text(json.dumps({'passed': len(cases), 'cases': cases}, indent=2) + '\n')
    print('PASS ARM64 filter runtime cases:', len(cases))


if __name__ == '__main__': main()
