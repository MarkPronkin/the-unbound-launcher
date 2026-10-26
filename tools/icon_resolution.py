"""Patch the headset installed-app icon loader and audit restoration of every class."""
from pathlib import Path
import subprocess

ROOT=Path(__file__).resolve().parents[1]


def patch_icon_resolution(original,build,java,env=None,*,restore=False,baseline=None):
    build=Path(build);build.mkdir(parents=True,exist_ok=True)
    source=build/'icons-input.dex';reference=build/'icons-baseline.dex';output=build/'icons-output.dex'
    source.write_bytes(original);reference.write_bytes(original if baseline is None else baseline)
    command=[str(Path(java)/'bin/java'),'--class-path',str(ROOT/'artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar'),str(ROOT/'tools/PatchIconResolutionDex.java')]
    subprocess.run([*command,'restore' if restore else 'patch',str(source),str(output),str(reference)],check=True,env=env)
    result=output.read_bytes()
    if not restore:
        restored=build/'icons-restored.dex'
        subprocess.run([*command,'restore',str(output),str(restored),str(reference)],check=True,env=env)
        if restored.read_bytes()!=original:raise ValueError('Icon hooks failed to restore the exact baseline')
    return result
