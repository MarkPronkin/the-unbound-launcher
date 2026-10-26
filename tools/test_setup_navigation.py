"""Exercise recorded launcher intents and reflection failures in the setup boundary."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_display_startup import ROOT, JAVA

STUBS = {
    'android/content/Context.java': 'package android.content;public class Context {public String getPackageName(){return "com.xreal.evapro.nebula";}}',
    'android/app/Activity.java': '''package android.app;public class Activity extends android.content.Context {
      public android.content.Intent intent;public android.content.Intent getIntent(){return intent;}}''',
    'android/content/ComponentName.java': '''package android.content;public class ComponentName {
      public String pkg,name;public ComponentName(Context c,String n){pkg=c.getPackageName();name=n;}}''',
    'android/content/Intent.java': '''package android.content;import java.util.*;public class Intent {
      public static final int FLAG_GRANT_READ_URI_PERMISSION=1,FLAG_GRANT_WRITE_URI_PERMISSION=2,
        FLAG_GRANT_PERSISTABLE_URI_PERMISSION=64,FLAG_GRANT_PREFIX_URI_PERMISSION=128;
      public static final String ACTION_MAIN="android.intent.action.MAIN",CATEGORY_LAUNCHER="android.intent.category.LAUNCHER",
        CATEGORY_LEANBACK_LAUNCHER="android.intent.category.LEANBACK_LAUNCHER";
      public int flags;public String action,data;public Object selector,clip;public ComponentName component;
      public Set<String> categories=new HashSet<>();public Map<String,Object> extras=new HashMap<>();
      public Intent(){}public Intent(Intent i){flags=i.flags;action=i.action;data=i.data;selector=i.selector;clip=i.clip;categories.addAll(i.categories);extras.putAll(i.extras);}
      public int getFlags(){return flags;}public Intent setFlags(int f){flags=f;return this;}
      public String getAction(){return action;}public Intent setAction(String a){action=a;return this;}
      public void removeCategory(String c){categories.remove(c);}public void setSelector(Object s){selector=s;}
      public Intent setComponent(ComponentName c){component=c;return this;}}''',
    'android/widget/Toast.java': '''package android.widget;public class Toast {public static int shown;
      public static Toast makeText(android.content.Context c,String s,int n){return new Toast();}public void show(){shown++;}}''',
    'android/util/Log.java': 'package android.util;public class Log {public static int e(String t,String m,Throwable e){return 0;}}',
    'dev/beampro/adb/AdbBootstrap.java': '''package dev.beampro.adb;public class AdbBootstrap {
      public static boolean reject,allowed;public static int setups;
      public static void setup(android.app.Activity a){if(reject)throw new IllegalStateException("cannot start");setups++;}
      public static boolean allowLaunch(android.app.Activity a){return allowed;}}''',
}

TEST = '''package dev.beampro.settings;
import android.content.Intent;import android.app.Activity;import dev.beampro.adb.AdbBootstrap;
public class SetupNavigationTest {
 static int count;static void check(boolean b){count++;if(!b)throw new AssertionError("check "+count);}
 public static void main(String[] args){
  Activity a=new Activity();a.intent=new Intent();a.intent.flags=0x10200000;
  a.intent.action=Intent.ACTION_MAIN;a.intent.categories.add(Intent.CATEGORY_LAUNCHER);a.intent.extras.put("marker",73);
  Intent next=SetupNavigation.forward(a,"dev.beampro.adb.AdbPairingActivity");
  check(next.flags==0&&next.action==null&&!next.categories.contains(Intent.CATEGORY_LAUNCHER));
  check(next.component.pkg.equals(a.getPackageName())&&next.component.name.equals("dev.beampro.adb.AdbPairingActivity"));
  check(next.extras.get("marker").equals(73));check(a.intent.flags==0x10200000&&a.intent.action.equals(Intent.ACTION_MAIN));
  a.intent.flags=0x7fffffff;a.intent.action="android.hardware.usb.action.USB_DEVICE_ATTACHED";
  a.intent.data="content://device/one";a.intent.clip=new Object();a.intent.selector=new Object();
  a.intent.categories.add("custom.category");a.intent.extras.put("device",new Object());
  next=SetupNavigation.forward(a,SetupNavigationTest.class);
  check(next.flags==195);check(next.action.equals(a.intent.action)&&next.data.equals(a.intent.data));
  check(next.clip==a.intent.clip&&next.extras.get("device")==a.intent.extras.get("device"));
  check(next.selector==null&&next.categories.contains("custom.category"));
  check(AdbEntry.setup(a)&&AdbBootstrap.setups==1);AdbBootstrap.reject=true;
  check(!AdbEntry.setup(a)&&android.widget.Toast.shown==1&&AdbBootstrap.setups==1);
  AdbBootstrap.allowed=false;check(!AdbEntry.allowLaunch(a));AdbBootstrap.allowed=true;check(AdbEntry.allowLaunch(a));
  System.out.println("PASS setup-navigation assertions: "+count);
 }
}'''


class SetupNavigationTests(unittest.TestCase):
    def test_recorded_launcher_flags_payload_preservation_and_failed_launch(self):
        with tempfile.TemporaryDirectory() as tmp:
            work=Path(tmp);paths=[]
            for name,text in {**STUBS,'dev/beampro/settings/SetupNavigationTest.java':TEST}.items():
                path=work/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text);paths.append(str(path))
            for name in ('SetupNavigation.java','AdbEntry.java'):
                paths.append(str(ROOT/'settings-ui/src/dev/beampro/settings'/name))
            subprocess.run([str(JAVA/'bin/javac'),'--release','8','-d',str(work),*paths],check=True)
            result=subprocess.run([str(JAVA/'bin/java'),'-cp',str(work),'dev.beampro.settings.SetupNavigationTest'],capture_output=True,text=True,timeout=15)
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertIn('PASS setup-navigation assertions: 12',result.stdout)
            print(result.stdout.strip())


if __name__=='__main__':unittest.main()
