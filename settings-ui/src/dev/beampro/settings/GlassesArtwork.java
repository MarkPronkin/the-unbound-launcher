package dev.beampro.settings;

import android.content.Context;
import android.graphics.drawable.Drawable;
import java.io.InputStream;

/** Original widget art where model-specific; original device art for shared-widget variants. */
final class GlassesArtwork {
    // Sampled from ASUS Pressroom's original red ROG logo PNG.
    static final int ROG_RED = 0xfff51928;
    final int type;
    final String name, asset;
    final boolean widget;

    private GlassesArtwork(int type, String name, String asset) {
        this.type = type; this.name = name; this.asset = asset;
        widget = asset != null && asset.startsWith("widget_");
    }

    static int connectedType() {
        try {
            Class<?> sdk = Class.forName("com.xreal.glassesdisplayplugevent.GlassesInitSetting");
            Object instance = sdk.getMethod("getInstance").invoke(null);
            return uiType(((Number) sdk.getMethod("checkGlassesType").invoke(instance)).intValue());
        } catch (ReflectiveOperationException | LinkageError | ClassCastException ignored) {
            return 0;
        }
    }

    private static int uiType(int type) {
        if (type != 6) return type;
        try {
            return ((Number) Class.forName("dev.beampro.port.RogR1Compat")
                    .getMethod("uiType", int.class).invoke(null, type)).intValue();
        } catch (ReflectiveOperationException | LinkageError unavailable) { return type; }
    }

    static GlassesArtwork forType(int type) {
        // Connection callbacks pass the native type directly, independently of the getter.
        type = uiType(type);
        switch (type) {
            case 2: return new GlassesArtwork(type, "XREAL Air", "widget_air");
            case 3: return new GlassesArtwork(type, "XREAL Air 2 Pro", "ota_air2pro");
            case 4: return new GlassesArtwork(type, "XREAL Air 2", "ota_air2");
            case 5: return new GlassesArtwork(type, "XREAL Air 2 Ultra", "widget_flora");
            case 6: return new GlassesArtwork(type, "XREAL One Pro", "widget_one_pro");
            case 7: return new GlassesArtwork(type, "XREAL One", "widget_one");
            case 9: return new GlassesArtwork(type, "XREAL 1S", "widget_1s");
            case 10: return new GlassesArtwork(type, "xbx a01", "ota_xbx_a01");
            case 11: return new GlassesArtwork(type, "xbx a01+", "ota_xbx_a01");
            case 20: return new GlassesArtwork(type, "XREAL 1 Pro+", "ota_one_pro_plus");
            case 1003: return new GlassesArtwork(type, "XREAL Rog R1", "widget_rog_r1");
            default: return new GlassesArtwork(0, "Your glasses", null);
        }
    }

    String filename() { return asset == null ? null : asset + (type == 1003 ? ".png" : ".webp"); }

    static Drawable load(Context context, String name) {
        try (InputStream stream = context.getAssets().open("settings/" + name)) {
            return Drawable.createFromStream(stream, name);
        } catch (java.io.IOException ignored) { return null; }
    }
}
