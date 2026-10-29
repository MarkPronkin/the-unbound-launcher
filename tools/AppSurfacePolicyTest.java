package dev.beampro.port;

import android.content.Context;
import android.opengl.EGL14;
import android.opengl.GLES20;
import java.io.File;
import java.nio.file.Files;

public final class AppSurfacePolicyTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    public static void main(String[] args)throws Exception {
        String supported="GL_EXT_other\tGL_EXT_texture_format_sRGB_override GL_EXT_last";
        check(!AppSurfacePolicy.useCopy(supported));
        check(AppSurfacePolicy.useCopy("GL_EXT_texture_format_sRGB_override_extra"));
        check(AppSurfacePolicy.useCopy("GL_OES_EGL_image_external"));
        check(!AppSurfacePolicy.useCopy(null));
        for(String extensions:new String[]{supported,"GL_OES_EGL_image_external",null}) {
            EGL14.reset("");GLES20.reset(extensions,true,true);
            check(AppSurfacePolicy.probe()==AppSurfacePolicy.useCopy(extensions));
            check(EGL14.destroyedContexts==1&&EGL14.destroyedSurfaces==1&&EGL14.terminated==1&&EGL14.released==1);
        }
        for(String failure:new String[]{"display","initialize","config","context","surface","current"}) {
            EGL14.reset(failure);
            try {AppSurfacePolicy.probe();throw new AssertionError("expected "+failure);}
            catch(IllegalStateException expected){checks++;}
            boolean initialized=!failure.equals("display")&&!failure.equals("initialize");
            check(EGL14.terminated==(initialized?1:0)&&EGL14.released==(initialized?1:0));
            check(EGL14.destroyedContexts==((failure.equals("surface")||failure.equals("current"))?1:0));
            check(EGL14.destroyedSurfaces==(failure.equals("current")?1:0));
        }
        File flag=new File(args[0],AppSurfacePolicy.FLAG);
        AppSurfacePolicy.write(flag,true);check(Files.readAllBytes(flag.toPath())[0]==1);
        Context context=new Context();context.dir=new File(args[0]);
        EGL14.reset("");GLES20.reset(supported,true,true);
        AppSurfacePolicy.install(context);check(Files.readAllBytes(flag.toPath())[0]==0);
        System.out.println("PASS app surface capability, EGL cleanup and stale-flag reset: "+checks);
    }
}
