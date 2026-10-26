#!/usr/bin/env python3
"""Build the dependency-free Android compatibility probe using an installed SDK/JDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))))
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    args = parser.parse_args()
    java_home = args.java_home
    if not java_home:
        java_home = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip())
    env = dict(os.environ, JAVA_HOME=str(java_home))
    env["PATH"] = str(java_home / "bin") + os.pathsep + env["PATH"]
    versions = [p for p in (args.sdk / "build-tools").iterdir() if re.fullmatch(r"\d+(\.\d+)+", p.name)]
    tools = max(versions, key=lambda p: tuple(map(int, p.name.split("."))))
    platform = args.sdk / "platforms/android-34/android.jar"
    if not platform.is_file():
        raise SystemExit("Install Android SDK platform 34 to build this probe")
    build = ROOT / "artifacts/probe/build"
    if build.exists():
        shutil.rmtree(build)
    (build / "classes").mkdir(parents=True)
    (build / "dex").mkdir()
    output = ROOT / "artifacts/probe"
    apk = output / "beam-port-probe.apk"

    def run(*command):
        subprocess.run([str(p) for p in command], env=env, check=True)

    sources = sorted((ROOT / "probe/src").rglob("*.java"))
    run(java_home / "bin/javac", "--release", "8", "-classpath", platform,
        "-d", build / "classes", *sources)
    run(java_home / "bin/jar", "cf", build / "classes.jar", "-C", build / "classes", ".")
    run(tools / "d8", "--min-api", "29", "--lib", platform, "--output", build / "dex", build / "classes.jar")
    run(tools / "aapt2", "link", "-I", platform, "--manifest", ROOT / "probe/AndroidManifest.xml",
        "-o", build / "base.apk")
    with zipfile.ZipFile(build / "base.apk") as source, zipfile.ZipFile(build / "unsigned.apk", "w") as target:
        for info in source.infolist():
            target.writestr(info, source.read(info))
        for dex in (build / "dex").glob("*.dex"):
            target.write(dex, dex.name, compress_type=zipfile.ZIP_DEFLATED)
    run(tools / "zipalign", "-f", "4", build / "unsigned.apk", build / "aligned.apk")
    keystore = output / "local-debug.p12"
    if not keystore.exists():
        run(java_home / "bin/keytool", "-genkeypair", "-noprompt", "-keystore", keystore,
            "-storetype", "PKCS12", "-storepass", "android", "-keypass", "android",
            "-alias", "port-probe", "-dname", "CN=Local Beam Port Probe", "-keyalg", "RSA",
            "-keysize", "2048", "-validity", "3650")
    run(tools / "apksigner", "sign", "--ks", keystore, "--ks-key-alias", "port-probe",
        "--ks-pass", "pass:android", "--out", apk, build / "aligned.apk")
    run(tools / "apksigner", "verify", "--verbose", apk)
    manifest = {"apk": apk.name, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
                "package": "dev.beampro.portprobe", "type": "compatibility-probe", "is_port": False,
                "build_tools": tools.name, "compile_sdk": 34, "min_sdk": 29, "target_sdk": 33}
    (output / "build.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(str(apk))


if __name__ == "__main__":
    main()
