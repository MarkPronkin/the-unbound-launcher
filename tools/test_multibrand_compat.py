"""Exercise Android 11+ publication and partial/backported display APIs without devices."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_display_startup import ROOT, JAVA
from test_managed_display_platform import SDK, STUBS


class MultibrandCompatTests(unittest.TestCase):
    def execute(self, stubs, sources, main, args, sdk=False):
        with tempfile.TemporaryDirectory() as tmp:
            work=Path(tmp); files=[]
            for name,code in stubs.items():
                path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(code);files.append(path)
            cp=['-cp',str(SDK)] if sdk else []
            subprocess.run([str(JAVA/'bin/javac'),'--release','8',*cp,'-d',str(work),
                            *map(str,files),*map(str,sources)],check=True,capture_output=True,text=True)
            result=subprocess.run([str(JAVA/'bin/java'),'-cp',str(work)+(':'+str(SDK) if sdk else ''),main,*args],
                                  text=True,capture_output=True,timeout=20)
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertIn('PASS',result.stdout)

    def test_provider_publication_contracts(self):
        legacy='android.os.Bundle call(String pkg,String tag,String authority,String method,String arg,android.os.Bundle extras);'
        modern='android.os.Bundle call(AttributionSource source,String authority,String method,String arg,android.os.Bundle extras);'
        for scenario in ('android11','modern','backported','denied'):
            with self.subTest(scenario=scenario):
                stubs={'android/os/Bundle.java':'package android.os; public class Bundle {}'}
                if scenario!='android11':
                    stubs['android/content/AttributionSource.java']='''package android.content; public class AttributionSource {
                      public static class Builder {
                        public Builder(int uid){if(uid!=2000)throw new AssertionError("Not shell");}
                        public Builder setPackageName(String pkg){if(!pkg.equals("com.android.shell"))throw new AssertionError("Wrong package");return this;}
                        public AttributionSource build(){return new AttributionSource();}
                      }}'''
                signature=legacy if scenario in ('android11','backported') else modern
                if scenario=='denied':signature+=legacy
                stubs['android/content/IContentProvider.java']='package android.content; public interface IContentProvider {'+signature+'}'
                self.execute(stubs,[ROOT/'bridge/src/dev/beampro/bridge/ProviderCallCompat.java',ROOT/'tools/ProviderCallCompatTest.java'],
                             'dev.beampro.bridge.ProviderCallCompatTest',[scenario])

    def test_independent_display_capabilities(self):
        methods={
            'enable':'void enableConnectedDisplay(int id);',
            'desktopGet':'int getConnectionPreference(String unique);',
            'desktopSet':'void setConnectionPreference(String unique,int value);',
            'modeGet':'android.view.Display.Mode getUserPreferredDisplayMode(int id);',
            'modeSet':'void setUserPreferredDisplayMode(int id,android.view.Display.Mode mode,boolean store);',
            'respectGet':'boolean shouldAlwaysRespectAppRequestedMode();',
            'respectSet':'void setShouldAlwaysRespectAppRequestedMode(boolean respect);',
        }
        for capabilities in ('','enable','enable,desktopGet','enable,desktopSet','desktopGet,desktopSet',
                             'enable,desktopGet,desktopSet','enable,modeGet','enable,modeSet',
                             'enable,modeGet,modeSet','enable,modeGet,modeSet,respectGet',
                             'enable,modeGet,modeSet,respectSet'):
            for ids in ('int[] getDisplayIds();','int[] getDisplayIds(boolean disabled);'):
                with self.subTest(capabilities=capabilities,ids=ids):
                    stubs=dict(STUBS)
                    signatures=''.join(methods[name] for name in capabilities.split(',') if name)
                    stubs['android/hardware/display/IDisplayManager.java']='''package android.hardware.display;
                      public interface IDisplayManager extends android.os.IBinder {'''+ids+signatures+'''
                        Object getDisplayInfo(int id);
                        public static class Stub {public static IDisplayManager asInterface(android.os.IBinder b){return (IDisplayManager)b;}}
                      }'''
                    sources=[ROOT/'bridge/src/dev/beampro/bridge'/name for name in
                             ('ManagedDisplay.java','ManagedDisplayPolicy.java','DisplaySnapshotReader.java')]
                    sources.append(ROOT/'tools/OemDisplayPlatformTest.java')
                    self.execute(stubs,sources,'dev.beampro.bridge.OemDisplayPlatformTest',[capabilities],sdk=True)


if __name__=='__main__':unittest.main()
