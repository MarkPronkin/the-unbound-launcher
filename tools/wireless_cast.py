"""Apply and round-trip audit the optional Miracast compatibility hooks."""
from pathlib import Path
import subprocess
from rog_r1 import IDENTITY_SOURCE

ROOT = Path(__file__).resolve().parents[1]


def patch_wireless_cast_dex(original, build, java, env):
    build = Path(build)
    build.mkdir(parents=True, exist_ok=True)
    before = build / "wireless-before.dex"
    patched = build / "wireless-patched.dex"
    restored = build / "wireless-restored.dex"
    before.write_bytes(original)
    command = [str(Path(java) / "bin/java"), "--class-path",
               str(ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"),
               str(ROOT / "tools/PatchWirelessCastDex.java")]
    subprocess.run([*command, "patch", str(before), str(patched)], check=True, env=env)
    subprocess.run([*command, "restore", str(patched), str(restored)], check=True, env=env)
    if restored.read_bytes() != original:
        raise ValueError("Wireless casting hooks did not restore the exact original DEX")
    return patched.read_bytes()


def compile_port_client(build, java, env, platform, tools):
    """Build current compatibility sources, including the casting capability check."""
    build = Path(build)
    classes = build / "client-classes"
    dex = build / "client-dex"
    classes.mkdir(parents=True, exist_ok=True)
    dex.mkdir(parents=True, exist_ok=True)
    jar = build / "client.jar"
    commands = [
        [Path(java) / "bin/javac", "--release", "8", "-classpath", platform, "-d", classes,
         IDENTITY_SOURCE, *sorted((ROOT / "port-client/src").rglob("*.java"))],
        [Path(java) / "bin/jar", "cf", jar, "-C", classes, "."],
        [Path(tools) / "d8", "--min-api", "29", "--lib", platform, "--output", dex, jar],
    ]
    for command in commands:
        subprocess.run([str(arg) for arg in command], check=True, env=env)
    subprocess.run([str(Path(java) / "bin/java"), "--class-path",
                    str(ROOT / "artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar"),
                    str(ROOT / "tools/PatchWirelessCastDex.java"), "verify-client",
                    str(dex / "classes.dex"), str(dex / "classes.dex")], check=True, env=env)
    return (dex / "classes.dex").read_bytes()
