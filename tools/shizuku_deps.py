"""Pinned official Shizuku API 13.1.5 artifacts, shared by both APK builders."""
import hashlib
import zipfile

SHIZUKU_DEPS = {
    'shizuku-api-13.1.5.aar': '4def9bde498ef8626614c2fc5db9af4749c86f16f6c33e3f5658d35e70bab59b',
    'shizuku-provider-13.1.5.aar': 'b0f18cd9812464ec171c53cac93a819fe411718a3965c311f01eb4de265381b3',
    'shizuku-aidl-13.1.5.aar': '33fe7191cdd69fcb66d649264f3b0c47acb2f3d6343afc05b98dbbff6f221963',
    'shizuku-shared-13.1.5.aar': '4659642c9339be0a26e9c65bb8648f7ad6d8f4a465f557993ccbc78802381635',
}


def shizuku_libraries(vendor, build):
    libraries = []
    for name, expected in SHIZUKU_DEPS.items():
        path = vendor / 'deps' / name
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError('Dependency checksum mismatch: ' + name)
        target = build / (path.stem + '.jar')
        with zipfile.ZipFile(path) as archive:
            target.write_bytes(archive.read('classes.jar'))
        libraries.append(target)
    return libraries
