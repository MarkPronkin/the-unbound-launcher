"""Remove phone-notification mirroring while retaining the two entry points called by Unity."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'compat/notifications'
ADAPTERS = ('com/xreal/evapro/NotifyHelper', 'ai/nreal/android/nebulaspaceproxy/NebulaNotificationListenerService')


def compile_notification_adapters(build, java, sdk, env=None):
    classes = build / 'classes'
    classes.mkdir(parents=True, exist_ok=True)
    # Descriptors only. The SDK supplies the actual types in the launcher DEX files.
    declarations = []
    for package, kind, name in [('com.xreal.evapro', 'class', 'NotifyInfo'),
                                ('com.xreal.evapro', 'interface', 'INotifyListener'),
                                ('ai.nreal.android.nebulaspaceproxy', 'interface', 'NotificationCallback')]:
        source = build / (name + '.java')
        source.write_text(f'package {package}; public {kind} {name} {{}}\n')
        declarations.append(source)
    platform = sdk / 'platforms/android-35/android.jar'
    subprocess.run([java / 'bin/javac', '--release', '8', '-cp', platform, '-d', classes,
                    *(SRC / (name + '.java') for name in ADAPTERS), *declarations], check=True, env=env)
    subprocess.run([sdk / 'build-tools/36.0.0/d8', '--min-api', '29', '--lib', platform, '--output', build,
                    *(classes / (name + '.class') for name in ADAPTERS)], check=True, env=env)
    return build / 'classes.dex'


def without_notification_mirroring(name, original, adapters, build, java, env=None):
    build.mkdir(parents=True, exist_ok=True)
    source, output = build / 'input.dex', build / 'output.dex'
    source.write_bytes(original)
    subprocess.run([java / 'bin/java', '--class-path', ROOT / 'artifacts/tools/jadx-1.5.6/lib/jadx-1.5.6-all.jar',
                    ROOT / 'tools/PatchNotificationsDex.java', name, source, adapters, output], check=True, env=env)
    return output.read_bytes()
