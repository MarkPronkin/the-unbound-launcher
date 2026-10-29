#!/usr/bin/env python3
"""Build a temporary app_process diagnostic for the ADB shell's display/input rights."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
from rog_r1 import IDENTITY_SOURCE

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
JAVA = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "17"], text=True).strip())
BUILD = ROOT / "artifacts/shell-probe/build"
TOOLS = SDK / "build-tools/36.0.0"
PLATFORM = SDK / "platforms/android-34/android.jar"
ENV = dict(os.environ, JAVA_HOME=str(JAVA))


def run(*args):
    subprocess.run([str(arg) for arg in args], env=ENV, check=True)


def main():
    if BUILD.exists():
        shutil.rmtree(BUILD)
    (BUILD / "classes").mkdir(parents=True)
    (BUILD / "dex").mkdir()
    run(JAVA / "bin/javac", "--release", "8", "-classpath", PLATFORM, "-d", BUILD / "classes",
        IDENTITY_SOURCE, *sorted((ROOT / "bridge/src").rglob("*.java")),
        ROOT / "settings-ui/src/dev/beampro/settings/NetworkDiagnostics.java",
        ROOT / "settings-ui/src/dev/beampro/settings/DisplayDiagnostics.java")
    run(JAVA / "bin/jar", "cf", BUILD / "classes.jar", "-C", BUILD / "classes", ".")
    run(TOOLS / "d8", "--min-api", "29", "--lib", PLATFORM, "--output", BUILD / "dex", BUILD / "classes.jar")
    with zipfile.ZipFile(ROOT / "artifacts/beam-pro-system/myglasses-dex.zip") as archive:
        (BUILD / "original.dex").write_bytes(archive.read("classes.dex"))
    server_dex = ROOT / "artifacts/shell-probe/server.dex"
    run(JAVA / "bin/java", "--class-path", ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar",
        ROOT / "tools/PatchLaunchDex.java", "server", BUILD / "original.dex", server_dex)
    output = ROOT / "artifacts/shell-probe/shell-probe.jar"
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for dex in (BUILD / "dex").glob("*.dex"):
            archive.write(dex, dex.name)
        archive.write(server_dex, "classes2.dex")
    print(output)

    client = ROOT / "artifacts/port-client"
    (client / "classes").mkdir(parents=True, exist_ok=True)
    run(JAVA / "bin/javac", "--release", "8", "-classpath", PLATFORM, "-d", client / "classes",
        IDENTITY_SOURCE, *sorted((ROOT / "port-client/src").rglob("*.java")))
    run(JAVA / "bin/jar", "cf", client / "classes.jar", "-C", client / "classes", ".")
    run(TOOLS / "d8", "--min-api", "29", "--lib", PLATFORM, "--output", client, client / "classes.jar")
    print(client / "classes.dex")


if __name__ == "__main__":
    main()
