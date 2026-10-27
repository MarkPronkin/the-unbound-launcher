package dev.beampro.settings;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.widget.Toast;

/** The original launcher's acceptance record, shared with its later policy-update checks. */
public final class TermsConsent {
    private static final String PREFERENCES = "nebula_app";
    private static final String FIRST = "PpTosConstant_FIRST_PP_TOS";
    private static final String REQUEST_TIME = "PpTosConstant_PP_TOS_REQUEST_TIME";
    private static final String TEMP_CACHE = "PpTosConstant_PP_TOS_TEMP_CACHE";
    private static final String PENDING = "unbound_terms_initialization_pending";

    private TermsConsent() {}

    public static boolean hasAccepted(Context context) {
        android.content.SharedPreferences state = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        return !state.getBoolean(FIRST, true) && !state.getBoolean(PENDING, false);
    }

    static boolean required(Context context) {
        return AdbEntry.available() && !hasAccepted(context);
    }

    static void openDocument(Activity activity, boolean privacy) {
        try {
            Class<?> helper = Class.forName("ai.nreal.nebula.activity.splash.pptos.PpTosWebHelper");
            helper.getMethod("showThis", Context.class).invoke(helper.getConstructor(boolean.class).newInstance(privacy), activity);
        } catch (ReflectiveOperationException error) {
            Log.e("UnboundTerms", "Cannot open legal document", error);
            Toast.makeText(activity, "Could not open this document. Please try again.", Toast.LENGTH_LONG).show();
        }
    }

    /** Called only by the explicit Agree action, after the original application is initialized. */
    static boolean accept(Activity activity) {
        if (hasAccepted(activity)) return true;
        try {
            Class<?> helper = Class.forName("ai.nreal.nebula.features.pp_tos.PPToSHelper");
            Object instance = helper.getMethod("getInstance").invoke(null);
            java.lang.reflect.Method firstAgree = helper.getMethod("firstAgree");
            java.lang.reflect.Method initialize = activity.getApplication().getClass().getMethod("agreePPTosToInit");
            // Use the same keys as SPUtils and commit before handing off or entering AR.
            // Keep the original accepted version fields and later update checks intact.
            if (!activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                    .putBoolean(FIRST, false).putLong(REQUEST_TIME, System.currentTimeMillis())
                    .putString(TEMP_CACHE, "").putBoolean(PENDING, true).commit()) {
                Toast.makeText(activity, "Could not save your agreement. Please try again.", Toast.LENGTH_LONG).show();
                return false;
            }
            firstAgree.invoke(instance);
            initialize.invoke(activity.getApplication());
            if (activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(PENDING).commit()) return true;
            Toast.makeText(activity, "Could not finish saving setup. Please try again.", Toast.LENGTH_LONG).show();
            return false;
        } catch (ReflectiveOperationException error) {
            Log.e("UnboundTerms", "Cannot complete terms acceptance", error);
            Toast.makeText(activity, "Could not finish setup. Please try again.", Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
