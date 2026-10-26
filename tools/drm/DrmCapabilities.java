package dev.beampro.drm;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.media.MediaDrm;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Looper;
import android.view.Display;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** Capability queries only: no licenses, media, unique DRM identifiers, or screen capture. */
public final class DrmCapabilities {
    private static final int PROTECTED_CONTENT = 0x32C0;
    private static final UUID WIDEVINE = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed");

    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new SecurityException("Run through ADB shell");
        Looper.prepareMainLooper();
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getMethod("systemMain").invoke(null);
        Context context = (Context) type.getMethod("getSystemContext").invoke(thread);
        JSONObject report = new JSONObject();
        report.put("uid", android.os.Process.myUid());
        report.put("model", Build.MODEL);
        report.put("sdk", Build.VERSION.SDK_INT);
        JSONObject permissions = new JSONObject();
        for (String name : new String[]{"CAPTURE_SECURE_VIDEO_OUTPUT", "CAPTURE_VIDEO_OUTPUT",
                "ACCESS_SURFACE_FLINGER", "MANAGE_ACTIVITY_TASKS", "ADD_TRUSTED_DISPLAY",
                "CREATE_VIRTUAL_DEVICE"}) {
            permissions.put(name, context.checkPermission("android.permission." + name,
                    android.os.Process.myPid(), android.os.Process.myUid()) == PackageManager.PERMISSION_GRANTED);
        }
        report.put("permissions", permissions);
        JSONArray displays = new JSONArray();
        for (Display display : context.getSystemService(DisplayManager.class).getDisplays()) {
            JSONObject item = new JSONObject();
            item.put("id", display.getDisplayId());
            item.put("name", display.getName());
            item.put("flags", display.getFlags());
            item.put("secure", (display.getFlags() & Display.FLAG_SECURE) != 0);
            item.put("protected_buffers", (display.getFlags() & Display.FLAG_SUPPORTS_PROTECTED_BUFFERS) != 0);
            displays.put(item);
        }
        report.put("displays", displays);
        JSONObject drmInfo = new JSONObject();
        try (MediaDrm drm = new MediaDrm(WIDEVINE)) {
            for (String name : new String[]{"vendor", "version", "description", "securityLevel", "systemId"}) {
                try { drmInfo.put(name, drm.getPropertyString(name)); }
                catch (Exception error) { drmInfo.put(name + "_error", error.toString()); }
            }
            drmInfo.put("max_hdcp", hdcp(drm.getMaxHdcpLevel()));
            drmInfo.put("connected_hdcp", hdcp(drm.getConnectedHdcpLevel()));
        } catch (Exception error) { drmInfo.put("error", error.toString()); }
        report.put("widevine", drmInfo);
        report.put("egl", egl());
        System.out.println("BEAM_DRM_CAPABILITIES " + report.toString());
        System.exit(0);
    }

    private static String hdcp(int level) {
        switch (level) {
            case MediaDrm.HDCP_NONE: return "none";
            case MediaDrm.HDCP_V1: return "1.x";
            case MediaDrm.HDCP_V2: return "2.0";
            case MediaDrm.HDCP_V2_1: return "2.1";
            case MediaDrm.HDCP_V2_2: return "2.2";
            case MediaDrm.HDCP_V2_3: return "2.3";
            case MediaDrm.HDCP_NO_DIGITAL_OUTPUT: return "no_digital_output";
            default: return "unknown:" + level;
        }
    }

    private static JSONObject egl() throws Exception {
        JSONObject result = new JSONObject();
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (!EGL14.eglInitialize(display, new int[2], 0, new int[2], 0)) {
            result.put("initialize_error", EGL14.eglGetError());
            return result;
        }
        try {
            String extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS);
            result.put("extensions", extensions);
            result.put("protected_content", has(extensions, "EGL_EXT_protected_content"));
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            boolean chosen = EGL14.eglChooseConfig(display, new int[]{
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE},
                    0, configs, 0, 1, count, 0);
            result.put("pbuffer_config", chosen && count[0] > 0);
            if (chosen && count[0] > 0) {
                result.put("ordinary_context", context(display, configs[0], false));
                if (has(extensions, "EGL_EXT_protected_content")) {
                    result.put("protected_context", context(display, configs[0], true));
                }
            }
        } finally { EGL14.eglTerminate(display); }
        return result;
    }

    private static boolean has(String extensions, String name) {
        return extensions != null && (" " + extensions + " ").contains(" " + name + " ");
    }

    private static JSONObject context(EGLDisplay display, EGLConfig config, boolean protect) throws Exception {
        JSONObject result = new JSONObject();
        int[] contextAttributes = protect ? new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                PROTECTED_CONTENT, EGL14.EGL_TRUE, EGL14.EGL_NONE}
                : new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        int[] surfaceAttributes = protect ? new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1,
                PROTECTED_CONTENT, EGL14.EGL_TRUE, EGL14.EGL_NONE}
                : new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
        EGLContext context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttributes, 0);
        result.put("created", !context.equals(EGL14.EGL_NO_CONTEXT));
        result.put("create_error", EGL14.eglGetError());
        if (context.equals(EGL14.EGL_NO_CONTEXT)) return result;
        if (protect) {
            int[] value = new int[1];
            result.put("protected_attribute_query", EGL14.eglQueryContext(display, context, PROTECTED_CONTENT, value, 0));
            result.put("protected_attribute", value[0]);
        }
        EGLSurface surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttributes, 0);
        result.put("surface_created", !surface.equals(EGL14.EGL_NO_SURFACE));
        result.put("surface_error", EGL14.eglGetError());
        if (protect && !surface.equals(EGL14.EGL_NO_SURFACE)) {
            int[] value = new int[1];
            result.put("protected_surface_query", EGL14.eglQuerySurface(display, surface, PROTECTED_CONTENT, value, 0));
            result.put("protected_surface_attribute", value[0]);
        }
        try {
            if (surface.equals(EGL14.EGL_NO_SURFACE)) return result;
            boolean current = EGL14.eglMakeCurrent(display, surface, surface, context);
            result.put("made_current", current);
            result.put("current_error", EGL14.eglGetError());
            if (current) {
                result.put("vendor", GLES20.glGetString(GLES20.GL_VENDOR));
                result.put("renderer", GLES20.glGetString(GLES20.GL_RENDERER));
                result.put("version", GLES20.glGetString(GLES20.GL_VERSION));
                result.put("protected_textures", has(GLES20.glGetString(GLES20.GL_EXTENSIONS), "GL_EXT_protected_textures"));
            }
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (!surface.equals(EGL14.EGL_NO_SURFACE)) EGL14.eglDestroySurface(display, surface);
            EGL14.eglDestroyContext(display, context);
        }
        return result;
    }
}
