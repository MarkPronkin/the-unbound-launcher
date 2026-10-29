"""Keep optional Bluetooth enumeration from aborting on unsupported HID profiles."""
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class BluetoothCompatTests(unittest.TestCase):
    def test_enumeration_failure_and_success_paths(self):
        java = Path(subprocess.check_output(
            ["/usr/libexec/java_home", "-v", "17"], text=True).strip()) / "bin"
        stubs = {
            "android/bluetooth/BluetoothDevice.java": """package android.bluetooth;
                public class BluetoothDevice {}""",
            "android/bluetooth/BluetoothAdapter.java": """package android.bluetooth;
                public class BluetoothAdapter {
                    public java.util.Set<BluetoothDevice> devices;
                    public RuntimeException failure;
                    public java.util.Set<BluetoothDevice> getBondedDevices() {
                        if (failure != null) throw failure;
                        return devices;
                    }
                }""",
            "android/bluetooth/BluetoothManager.java": """package android.bluetooth;
                public class BluetoothManager {
                    public java.util.List<BluetoothDevice> devices;
                    public RuntimeException failure;
                    public int profile;
                    public java.util.List<BluetoothDevice> getConnectedDevices(int profile) {
                        this.profile = profile;
                        if (failure != null) throw failure;
                        return devices;
                    }
                }""",
        }
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            sources = []
            for name, code in stubs.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(code)
                sources.append(path)
            sources += [ROOT / "port-client/src/dev/beampro/port/BluetoothCompat.java",
                        ROOT / "tools/BluetoothCompatTest.java"]
            subprocess.run([str(java / "javac"), "--release", "8", "-d", tmp,
                            *map(str, sources)], check=True, timeout=30)
            result = subprocess.run([str(java / "java"), "-cp", tmp,
                                     "dev.beampro.port.BluetoothCompatTest"],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("PASS Bluetooth enumeration checks:", result.stdout)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
