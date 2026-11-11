package dev.beampro.settings;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.AtomicFile;
import dalvik.system.DexClassLoader;
import dev.beampro.port.ExperimentalOptionsCompat;
import dev.beampro.settings.preview.SettingsPreviewActivity;
import java.lang.reflect.InvocationTargetException;
import java.io.File;

/** Real Android persistence, production switches and the patched stock sound helper. */
public final class ExperimentalOptionsChecks extends Instrumentation {
    int passed;
    Bundle arguments;
    SettingsPreviewActivity activity;

    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        try {
            reset();
            checkOtherProcess(false);
            open();
            runOnMainSync(() -> {
                require(!toggle(ExperimentalOptions.QUIET_PHOTOS).isChecked(), "Shutter sound enabled by default");
                require(!toggle(ExperimentalOptions.SKIP_SCREEN_OFF_CONFIRMATION).isChecked(), "Confirmation shown by default");
                require(toggle(ExperimentalOptions.SKIP_SCREEN_OFF_CONFIRMATION).getContentDescription().toString().contains("volume"),
                        "Volume recovery is explained beside the switch");
                toggle(ExperimentalOptions.QUIET_PHOTOS).performClick();
                require(ExperimentalOptions.enabled(activity, ExperimentalOptions.QUIET_PHOTOS), "Quiet photo switch saves");
                require(!ExperimentalOptionsCompat.skipScreenOffConfirmation(activity), "Options are independent");
                activity.findViewById(0x00bea001).findViewWithTag("experimental-" + ExperimentalOptions.SKIP_SCREEN_OFF_CONFIRMATION).performClick();
                require(ExperimentalOptionsCompat.skipScreenOffConfirmation(activity), "Whole row saves to the client preference contract");
                activity.finish();
            });
            waitForIdleSync(); open();
            checkOtherProcess(true);
            runOnMainSync(() -> {
                require(toggle(ExperimentalOptions.QUIET_PHOTOS).isChecked(), "Quiet photo preference survives recreation");
                require(toggle(ExperimentalOptions.SKIP_SCREEN_OFF_CONFIRMATION).isChecked(), "Confirmation preference survives recreation");
                activity.setConnectedModel(7);
                require(toggle(ExperimentalOptions.QUIET_PHOTOS).isChecked(), "Connection refresh preserves preference");
            });
            int shutter = context.getResources().getIdentifier("kaca", "raw", context.getPackageName());
            require(shutter != 0, "Shutter resource fixture exists");
            require(ExperimentalOptionsCompat.suppressPhotoSound(context, shutter), "Client reads the UI's saved shutter preference");
            require(!ExperimentalOptionsCompat.suppressPhotoSound(context, android.R.drawable.ic_menu_camera), "Other resources retain sound");
            require(!ExperimentalOptionsCompat.suppressPhotoSound(context, 0), "Unknown resources retain stock behavior");
            require(!ExperimentalOptionsCompat.suppressPhotoSound(null, shutter), "Absent context retains stock behavior");
            checkStockHelper(arguments.getString("photo_sound_dex"), shutter, true);
            checkStockHelper(arguments.getString("original_sound_dex"), shutter, false);
            runOnMainSync(() -> {
                toggle(ExperimentalOptions.QUIET_PHOTOS).performClick();
                toggle(ExperimentalOptions.SKIP_SCREEN_OFF_CONFIRMATION).performClick();
                require(!ExperimentalOptionsCompat.suppressPhotoSound(activity, shutter), "Disabling quiet photos immediately restores sound");
                require(!ExperimentalOptionsCompat.skipScreenOffConfirmation(activity), "Disabling skip immediately restores confirmation");
                activity.finish();
            });
            waitForIdleSync(); open();
            checkOtherProcess(false);
            runOnMainSync(() -> {
                require(!toggle(ExperimentalOptions.QUIET_PHOTOS).isChecked()
                        && !toggle(ExperimentalOptions.SKIP_SCREEN_OFF_CONFIRMATION).isChecked(), "Both disabled states survive recreation");
                activity.finish();
            });
            result.putString("stream", "PASS experimental options Android/UI checks: " + passed + "\n");
            finish(-1, result);
        } catch (Throwable failure) {
            result.putString("stream", "FAIL experimental options after " + passed + ": " + android.util.Log.getStackTraceString(failure));
            finish(0, result);
        } finally {
            reset();
        }
    }

    void reset() {
        for (String key : new String[]{ExperimentalOptions.QUIET_PHOTOS, ExperimentalOptions.SKIP_SCREEN_OFF_CONFIRMATION})
            new AtomicFile(new File(getTargetContext().getFilesDir(), ExperimentalOptions.FILE + "-" + key)).delete();
    }

    void checkOtherProcess(boolean expected) {
        Bundle state = getTargetContext().getContentResolver().call(Uri.parse("content://dev.beampro.settings.preview.experimental"), "read", null, null);
        require(state != null && state.getInt("pid") != android.os.Process.myPid(), "Client fixture runs in a separate process");
        require(state.getBoolean("quiet") == expected && state.getBoolean("skip") == expected, "Already-running client observes current choices: " + expected);
    }

    public static final class ClientProvider extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            Context context = getContext();
            int resource = context.getResources().getIdentifier("kaca", "raw", context.getPackageName());
            Bundle state = new Bundle();
            state.putInt("pid", android.os.Process.myPid());
            state.putBoolean("quiet", ExperimentalOptionsCompat.suppressPhotoSound(context, resource));
            state.putBoolean("skip", ExperimentalOptionsCompat.skipScreenOffConfirmation(context));
            return state;
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }

    void checkStockHelper(String dex, int shutter, boolean patched) throws Exception {
        if (dex == null) throw new IllegalArgumentException("Pass both patched and original sound DEX paths");
        Context context = getTargetContext();
        ClassLoader loader = new DexClassLoader(dex, context.getCodeCacheDir().getAbsolutePath(), null, context.getClassLoader());
        Class<?> helper = loader.loadClass("ai.nreal.android.toolkitsproxy.MediaActionHelper");
        Object sound = helper.getConstructor(Context.class).newInstance(context);
        // Without init(), the original body reaches a null SoundPool. Suppression
        // must return before that body; the negative controls must still reach it.
        for (boolean quiet : new boolean[]{true, false, true}) {
            ExperimentalOptions.save(context, ExperimentalOptions.QUIET_PHOTOS, quiet);
            for (int resource : new int[]{shutter, android.R.drawable.ic_menu_camera, 0}) {
                boolean returned = false;
                try { helper.getMethod("play", int.class, float.class).invoke(sound, resource, .75f); returned = true; }
                catch (InvocationTargetException error) {
                    if (!(error.getCause() instanceof NullPointerException)) throw error;
                }
                require(returned == (patched && quiet && resource == shutter), "Stock helper guard and negative control: " + patched + "/" + quiet + "/" + resource);
            }
        }
    }

    void open() {
        activity = (SettingsPreviewActivity) startActivitySync(new Intent(getTargetContext(), SettingsPreviewActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
    }
    SettingsUi.SignatureSwitch toggle(String key) { return activity.findViewById(0x00bea001).findViewWithTag(key); }
    void require(boolean value, String message) { if (!value) throw new AssertionError(message); passed++; }
}
