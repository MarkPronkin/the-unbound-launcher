package dev.beampro.port;

import android.opengl.GLES20;
import android.util.Log;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Pair each app texture's actual GL conversion state with its copy material. */
public final class AppColorCompat {
    static final int EXTERNAL=0x8D65, OVERRIDE=0x8FBF, SRGB=0x8C40;
    private static final String EXTENSION="GL_EXT_texture_format_sRGB_override";
    private static final Map<Object,Boolean> software=Collections.synchronizedMap(new WeakHashMap<Object,Boolean>());
    private AppColorCompat() {}

    public static int generateExternalTexture(Object owner) {
        int[] ids=new int[1];
        GLES20.glGenTextures(1,ids,0);
        GLES20.glBindTexture(EXTERNAL,ids[0]);
        GLES20.glTexParameteri(EXTERNAL,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(EXTERNAL,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(EXTERNAL,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(EXTERNAL,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        int setupError=GLES20.glGetError();
        if(setupError!=GLES20.GL_NO_ERROR)Log.w("UnboundColor","Texture setup GL error="+setupError);
        boolean hardware=false;
        String extensions=GLES20.glGetString(GLES20.GL_EXTENSIONS);
        if(hasExtension(extensions,EXTENSION)) {
            GLES20.glTexParameteri(EXTERNAL,OVERRIDE,SRGB);
            int setError=GLES20.glGetError();
            int[] actual=new int[1];
            GLES20.glGetTexParameteriv(EXTERNAL,OVERRIDE,actual,0);
            int queryError=GLES20.glGetError();
            hardware=setError==GLES20.GL_NO_ERROR&&queryError==GLES20.GL_NO_ERROR&&actual[0]==SRGB;
            if(!hardware) {
                // Never use shader decode over a partially accepted override.
                GLES20.glTexParameteri(EXTERNAL,OVERRIDE,0);
                int resetError=GLES20.glGetError();
                Log.w("UnboundColor","sRGB override unavailable: set="+setError+" query="+queryError+" reset="+resetError);
                if(resetError!=GLES20.GL_NO_ERROR)
                    hardware=queryError==GLES20.GL_NO_ERROR?actual[0]==SRGB:setError==GLES20.GL_NO_ERROR;
            }
        }
        software.put(owner,!hardware);
        Log.i("UnboundColor","texture="+ids[0]+" decode="+(hardware?"hardware":"shader")
                +" renderer="+GLES20.glGetString(GLES20.GL_RENDERER));
        return ids[0];
    }

    // Called through the original AndroidSurfaceTexture wrapper on Unity's thread.
    // No GL calls here: capability negotiation happened on the render thread.
    public static boolean needsSoftwareDecode(Object owner) {
        Boolean value=software.get(owner);
        return value!=null&&value;
    }

    static boolean hasExtension(String list,String wanted) {
        if(list==null)return false;
        for(String token:list.split("\\s+"))if(wanted.equals(token))return true;
        return false;
    }
}
