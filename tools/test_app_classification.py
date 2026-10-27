"""Run app category policy using the production helper, including recovery after failures."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_display_startup import ROOT, JAVA


class AppClassificationTests(unittest.TestCase):
    def test_category_policy(self):
        stubs = {
            "android/content/pm/ApplicationInfo.java": "package android.content.pm; public class ApplicationInfo { public static final int CATEGORY_UNDEFINED=-1,CATEGORY_GAME=0; public int uid,category; }",
            "android/content/pm/PackageManager.java": """package android.content.pm; public class PackageManager {
                public static final int SIGNATURE_MATCH=0; public String[] getPackagesForUid(int uid){return null;}
                public String getInstallerPackageName(String p){return null;} public int checkSignatures(String a,String b){return 0;}
                public void setInstallerPackageName(String p,String i){} public void setApplicationCategoryHint(String p,int c){} }""",
        }
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            sources = []
            for name, source in stubs.items():
                path = work / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source)
                sources.append(path)
            sources += [ROOT / "bridge/src/dev/beampro/bridge/AppClassification.java", ROOT / "tools/AppClassificationTest.java"]
            subprocess.run([str(JAVA / "bin/javac"), "--release", "8", "-d", tmp, *map(str, sources)], check=True)
            result = subprocess.run([str(JAVA / "bin/java"), "-cp", tmp, "dev.beampro.bridge.AppClassificationTest", tmp],
                                    capture_output=True, text=True, check=True)
            self.assertIn("PASS app classification assertions:", result.stdout)
            print(result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
