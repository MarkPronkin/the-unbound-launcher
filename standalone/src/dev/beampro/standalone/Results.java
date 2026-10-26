package dev.beampro.standalone;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** App-owned evidence. No shell, helper Binder, or external storage is used. */
public final class Results {
    private static JSONObject value;
    private static Context context;
    public static synchronized void init(Context owner) {
        context = owner.getApplicationContext();
        if (value != null) return;
        AtomicFile existing=new AtomicFile(new File(context.getFilesDir(), "standalone-results.json"));
        try (java.io.FileInputStream input=existing.openRead()) {
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
            byte[] block=new byte[8192]; int read;
            while((read=input.read(block))!=-1) bytes.write(block,0,read);
            JSONObject previous=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
            if(previous.optInt("uid")==Process.myUid() && previous.optInt("schema")==1) {
                value=previous;
                if(value.optBoolean("capture_active")) {
                    put(value,"capture_active",false); put(value,"capture_stop_reason","previous_process_ended");
                    JSONObject split=value.optJSONObject("split_capture");
                    if(split!=null && !split.optBoolean("complete")) put(split,"interrupted",true);
                    save();
                }
                return;
            }
        } catch(java.io.IOException|org.json.JSONException ignored) { /* Fresh install or incomplete previous report. */ }
        value = new JSONObject();
        put(value, "schema", 1);
        put(value, "model", Build.MODEL);
        put(value, "sdk", Build.VERSION.SDK_INT);
        put(value, "uid", Process.myUid());
        put(value, "shell_helper_used", false);
        put(value, "privileged_permissions_requested", false);
        put(value, "independent_virtual_apps_passed", false);
        put(value, "split_capture_two_apps_passed", false);
        put(value, "ar_space_integration_tested", false);
        put(value, "virtual_display_cases", new JSONArray());
        save();
    }
    public static void put(JSONObject object, String key, Object data) {
        try { object.put(key, data == null ? JSONObject.NULL : data); }
        catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }
    public static synchronized void set(String key, Object data) { put(value, key, data); save(); }
    public static synchronized void permissions(Context owner) {
        JSONObject permissions=new JSONObject();
        for(String name:new String[]{"INJECT_EVENTS","ADD_TRUSTED_DISPLAY","ACTIVITY_EMBEDDING","CAPTURE_VIDEO_OUTPUT","CAPTURE_SECURE_VIDEO_OUTPUT","WRITE_SECURE_SETTINGS"}) {
            put(permissions,name,owner.checkSelfPermission("android.permission."+name)==android.content.pm.PackageManager.PERMISSION_GRANTED);
        }
        put(value,"effective_privileged_permissions",permissions); save();
    }
    public static synchronized void addCase(JSONObject data) {
        value.optJSONArray("virtual_display_cases").put(data); save();
    }
    public static synchronized String text() {
        try { return value.toString(2); } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }
    private static void save() {
        AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "standalone-results.json"));
        FileOutputStream stream = null;
        try {
            stream = file.startWrite(); stream.write(text().getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream);
        } catch (java.io.IOException error) {
            if (stream != null) file.failWrite(stream);
            android.util.Log.e("UnboundStandalone", "Cannot save results", error);
        }
    }
}
