package dev.beampro.port;

import android.opengl.GLES20;

public final class AppColorCompatTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static Object texture(String extensions,boolean accept,boolean query) {
        GLES20.reset(extensions,accept,query);
        Object owner=new Object();
        check(AppColorCompat.generateExternalTexture(owner)==41,"texture ID retained");
        check(GLES20.bound==AppColorCompat.EXTERNAL,"external texture target retained");
        check(GLES20.filters==4,"filtering and wrapping retained");
        return owner;
    }
    public static void main(String[] args) {
        String extension="GL_EXT_texture_format_sRGB_override";
        Object unsupported=texture("GL_OES_EGL_image_external",false,false);
        check(AppColorCompat.needsSoftwareDecode(unsupported),"unsupported GPU selects shader decode");
        check(GLES20.overrides==0,"unsupported enum never sent");
        Object supported=texture("GL_EXT_other "+extension+"\tGL_EXT_last",true,true);
        check(!AppColorCompat.needsSoftwareDecode(supported),"confirmed GPU decode retains stock shader");
        check(GLES20.override==AppColorCompat.SRGB,"hardware override enabled");
        check(AppColorCompat.needsSoftwareDecode(unsupported),"each texture keeps its own decision");
        Object rejected=texture(extension,false,true);
        check(AppColorCompat.needsSoftwareDecode(rejected),"advertised but rejected override falls back");
        check(GLES20.override==0,"fallback clears override");
        Object unreadable=texture(extension,true,false);
        check(AppColorCompat.needsSoftwareDecode(unreadable),"unverified state resets and uses software");
        check(GLES20.override==0,"no double decode after failed readback");
        Object ignored=texture(extension+"_extra",true,true);
        check(AppColorCompat.needsSoftwareDecode(ignored)&&GLES20.overrides==0,"extension names matched as whole tokens");
        Object absent=texture(null,true,true);
        check(AppColorCompat.needsSoftwareDecode(absent),"missing extension string falls back");
        check(!AppColorCompat.needsSoftwareDecode(new Object()),"unknown owner preserves stock behavior");
        check(!AppColorCompat.needsSoftwareDecode(supported),"later probes do not change another texture");
        int calls=GLES20.calls;
        AppColorCompat.needsSoftwareDecode(absent);
        check(calls==GLES20.calls,"Unity-thread getter never invokes GL");
        System.out.println("PASS: "+checks+" app-color capability assertions");
    }
}
