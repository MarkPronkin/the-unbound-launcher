package dev.beampro.identitychecks;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import java.io.File;
import java.lang.reflect.*;

/** Tests the installed production APK, without adding test classes to that APK. */
public final class PackageIdentityDeviceChecks extends Instrumentation {
    private static final String PACKAGE = "dev.markpronkin.unbound";
    private int passed;
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments=args; start(); }
    private void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        passed++;
    }
    private Class<?> type(Context context, String name) throws Exception {
        return Class.forName(name, false, context.getClassLoader());
    }
    private Method method(Context context, String owner, String name, Class<?>... parameters) throws Exception {
        Method result=type(context,owner).getDeclaredMethod(name,parameters);
        result.setAccessible(true); return result;
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context=getTargetContext();
            PackageManager pm=context.getPackageManager();
            check(context.getPackageName().equals(PACKAGE), "Installed application ID");
            PackageInfo info=pm.getPackageInfo(PACKAGE, PackageManager.GET_ACTIVITIES|PackageManager.GET_SERVICES
                    |PackageManager.GET_PROVIDERS|PackageManager.GET_RECEIVERS|PackageManager.GET_SIGNING_CERTIFICATES);
            check(info.signingInfo!=null, "Installed signing certificate");
            int label=context.getResources().getIdentifier("app_name","string",PACKAGE);
            check(label!=0, "Resources resolve under the new package");
            check(context.getResources().getResourcePackageName(label).equals(PACKAGE), "Resource table package");
            check(context.getString(label).equals("The Unbound Launcher"), "Launcher label preserved");
            for(ComponentInfo[] list:new ComponentInfo[][]{info.activities,info.services,info.providers,info.receivers}) {
                if(list==null)continue;
                for(ComponentInfo component:list) {
                    check(component.packageName.equals(PACKAGE), "Component owner: "+component.name);
                    check(type(context,component.name)!=null, "Original component class loads: "+component.name);
                }
            }
            for(ProviderInfo provider:info.providers) {
                check(provider.authority.startsWith(PACKAGE), "Provider relocated: "+provider.authority);
                check(pm.resolveContentProvider(provider.authority,0).packageName.equals(PACKAGE), "Provider resolution");
            }
            check(context.getFilesDir().getCanonicalFile().equals(new File("/data/user/0/"+PACKAGE+"/files").getCanonicalFile()), "Private storage path");
            check(context.getExternalFilesDir(null).getPath().contains("/Android/data/"+PACKAGE+"/files"), "Unity persistent storage");
            Class<?> stereo=type(context,"dev.beampro.settings.ExperimentalStereo");
            Method save=stereo.getMethod("save",Context.class,boolean.class), enabled=stereo.getMethod("enabled",Context.class);
            boolean previous=(Boolean)enabled.invoke(null,context);
            try {
                save.invoke(null,context,true);
                check((Boolean)enabled.invoke(null,context), "Experimental stereo saves in the native reader's package path");
                save.invoke(null,context,false);
                check(!(Boolean)enabled.invoke(null,context), "Experimental stereo disables");
            } finally { save.invoke(null,context,previous); }
            Uri bridge=Uri.parse("content://"+PACKAGE+".unbound.bridge");
            Bundle progress=new Bundle();
            progress.putString("identity_stage","waiting_for_helper");
            sendStatus(1,progress);
            Bundle binder=null;
            long deadline=SystemClock.elapsedRealtime()+15000;
            do {
                binder=context.getContentResolver().call(bridge,"get",null,null);
                if(binder!=null&&binder.getBinder("binder")!=null)break;
                SystemClock.sleep(200);
            } while(SystemClock.elapsedRealtime()<deadline);
            check(binder!=null&&binder.getBinder("binder")!=null&&binder.getBinder("binder").pingBinder(), "Bundled helper publishes to the new provider");
            Field authority=type(context,"dev.beampro.adb.AdbBootstrap").getField("AUTHORITY");
            check(authority.get(null).equals(bridge.getAuthority()), "ADB bootstrap authority");
            for(String name:new String[]{"dev.beampro.port.UsbPermissionCompat","dev.beampro.adb.AutoGlassesReceiver",
                                        "dev.beampro.settings.LauncherReload"}) {
                Field field=type(context,name).getDeclaredField("PACKAGE");field.setAccessible(true);
                check(field.get(null).equals(PACKAGE), "USB/automatic entry/AR restart package: "+name);
            }
            check(context.getContentResolver().getType(Uri.parse("content://"+PACKAGE+".unbound.updates/update.apk"))
                    .equals("application/vnd.android.package-archive"), "Update provider URI");
            check(context.getContentResolver().getType(Uri.parse("content://"+PACKAGE+".unbound.debug/check.zip"))
                    .equals("application/zip"), "Debug provider URI");
            Method verify=method(context,"dev.beampro.settings.AppUpdater","verifyApk",Context.class,File.class);
            PackageInfo update=(PackageInfo)verify.invoke(null,context,new File(arguments.getString("valid")));
            check(update.packageName.equals(PACKAGE), "Updater accepts a newer APK with the new ID and same key");
            try {
                verify.invoke(null,context,new File(arguments.getString("legacy")));
                throw new AssertionError("Legacy package accepted as an update");
            } catch(InvocationTargetException expected) {
                check(expected.getCause().getMessage().contains("not an update"), "Updater rejects the old package");
            }
            result.putBoolean("passed",true);
            result.putString("stream","PASS production package identity checks: "+passed+"\n");
            finish(-1,result);
        } catch(Throwable error) {
            android.util.Log.e("PackageIdentityChecks","Checks failed",error);
            result.putString("stream","FAIL after "+passed+": "+error+"\n");
            finish(0,result);
        }
    }
}
