package dev.beampro.port;

import android.content.Context;
import android.media.MediaDrm;
import android.opengl.EGL14;
import android.opengl.GLES20;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class AppSurfacePolicyTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    private static int flag(File file) {
        try {byte[] data=Files.readAllBytes(file.toPath());check(data.length==1);return data[0];}
        catch(Exception failure){throw new AssertionError(failure);}
    }
    private static void latch(CountDownLatch latch) {
        try {if(!latch.await(2,TimeUnit.SECONDS))throw new AssertionError("worker deadline");}
        catch(InterruptedException failure){throw new AssertionError(failure);}
    }
    public static void main(String[] args)throws Exception {
        String supported="GL_EXT_other\tGL_EXT_texture_format_sRGB_override GL_EXT_last";
        check(!AppSurfacePolicy.useCopy(supported));
        check(AppSurfacePolicy.useCopy("GL_EXT_texture_format_sRGB_override_extra"));
        check(AppSurfacePolicy.useCopy("GL_OES_EGL_image_external"));
        check(AppSurfacePolicy.useCopy(null));
        for(String extensions:new String[]{supported,"GL_OES_EGL_image_external",null}) {
            EGL14.reset("");GLES20.reset(extensions,true,true);MediaDrm.reset("");
            check(AppSurfacePolicy.probe()==AppSurfacePolicy.useCopy(extensions));
            check(EGL14.destroyedContexts==1&&EGL14.destroyedSurfaces==1&&EGL14.terminated==1&&EGL14.released==1);
            int expected=extensions==supported?1:0;
            check(MediaDrm.creates==expected&&MediaDrm.opens==expected&&MediaDrm.closes==expected&&MediaDrm.releases==expected);
        }
        // The Moto's openSession failure must select copies even when its GPU
        // advertises the sRGB override. Exercise the other platform failures too.
        for(String failure:new String[]{"constructor","not-provisioned","busy","plugin","null","empty","close","release"}) {
            EGL14.reset("");GLES20.reset(supported,true,true);MediaDrm.reset(failure);
            check(AppSurfacePolicy.probe());
            check(MediaDrm.creates==1);
            check(MediaDrm.opens==(failure.equals("constructor")?0:1));
            check(MediaDrm.closes==((failure.equals("empty")||failure.equals("close")||failure.equals("release"))?1:0));
            check(MediaDrm.releases==(failure.equals("constructor")?0:1));
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
        AppSurfacePolicy.write(flag,false);check(flag(flag)==0);
        AppSurfacePolicy.select(flag,()->{
            check(flag(flag)==1); // Stale overlay preference is cleared first.
            return false;
        },1000);
        check(flag(flag)==0);
        AppSurfacePolicy.select(flag,()->{throw new IllegalStateException("broken driver");},1000);
        check(flag(flag)==1);
        AppSurfacePolicy.select(flag,()->true,1000);check(flag(flag)==1);

        CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);
        Thread[] worker=new Thread[1];
        AppSurfacePolicy.select(flag,()->{
            worker[0]=Thread.currentThread();entered.countDown();latch(resume);return false;
        },10);
        latch(entered);check(flag(flag)==1);resume.countDown();worker[0].join(1000);
        check(!worker[0].isAlive());check(flag(flag)==1); // Late success cannot publish.

        CountDownLatch enteredInterrupted=new CountDownLatch(1),resumeInterrupted=new CountDownLatch(1);
        Thread.currentThread().interrupt();
        AppSurfacePolicy.select(flag,()->{
            worker[0]=Thread.currentThread();enteredInterrupted.countDown();latch(resumeInterrupted);return false;
        },1000);
        check(Thread.interrupted());check(flag(flag)==1);
        latch(enteredInterrupted);resumeInterrupted.countDown();worker[0].join(1000);
        check(!worker[0].isAlive());check(flag(flag)==1);

        Context context=new Context();context.dir=new File(args[0]);
        EGL14.reset("");GLES20.reset(supported,true,true);MediaDrm.reset("");
        AppSurfacePolicy.install(context);check(flag(flag)==0);
        AppSurfacePolicy.install(context);check(MediaDrm.opens==1);
        System.out.println("PASS app surface capability, DRM cleanup, timeout, interruption and stale-flag reset: "+checks);
    }
}
