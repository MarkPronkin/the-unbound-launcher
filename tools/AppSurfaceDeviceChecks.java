package dev.beampro.port;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.pm.PackageManager;
import android.os.Bundle;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exercise the candidate's actual policy DEX and Android AtomicFile, under an app UID. */
public final class AppSurfaceDeviceChecks extends Instrumentation {
    private int checks;
    private void check(boolean value,String message) {
        if(!value)throw new AssertionError(message);
        checks++;
    }
    private int flag(File file)throws Exception {
        byte[] bytes=Files.readAllBytes(file.toPath());
        check(bytes.length==1,"single-byte native routing contract");
        return bytes[0];
    }
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            File file=new File(getTargetContext().getFilesDir(),AppSurfacePolicy.FLAG);
            check(getTargetContext().checkSelfPermission("android.permission.INTERNET")
                ==PackageManager.PERMISSION_DENIED,"fixture cannot provision over the network");
            boolean drmReady=AppSurfacePolicy.protectedSessionReady();
            result.putString("live_drm_ready",Boolean.toString(drmReady));
            // This uses the real emulator EGL and DRM services. Host regressions
            // supply the exact NotProvisionedException from the Moto capture.
            boolean liveCopy=AppSurfacePolicy.probe();
            result.putString("live_probe_copy",Boolean.toString(liveCopy));
            AppSurfacePolicy.select(file,()->liveCopy,1000);
            check(flag(file)==(liveCopy?1:0),"live capability result reaches native flag");

            AppSurfacePolicy.select(file,()->false,1000);
            check(flag(file)==0,"ready overlay remains available");
            AppSurfacePolicy.select(file,()->{
                throw new IllegalStateException("simulated driver/plugin failure");
            },1000);
            check(flag(file)==1,"driver exception retains copy routing");
            AppSurfacePolicy.select(file,()->true,1000);
            check(flag(file)==1,"unavailable prerequisite uses copies");

            CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);
            Thread[] worker=new Thread[1];
            AppSurfacePolicy.select(file,()->{
                worker[0]=Thread.currentThread();entered.countDown();
                try {
                    if(!resume.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test deadline");
                } catch(InterruptedException failure){throw new IllegalStateException(failure);}
                return false;
            },10);
            check(entered.await(2,TimeUnit.SECONDS),"slow probe entered");
            check(flag(file)==1,"timeout retains copies");
            resume.countDown();worker[0].join(2000);
            check(!worker[0].isAlive(),"timed-out probe finishes");
            check(flag(file)==1,"late success cannot overwrite route");

            AppSurfacePolicy.write(file,false);
            AppSurfacePolicy.select(file,()->{
                try {if(flag(file)!=1)throw new IllegalStateException("stale overlay flag");}
                catch(Exception failure){throw new IllegalStateException(failure);}
                return true;
            },1000);
            check(flag(file)==1,"stale overlay flag reset before probing");
            result.putString("result","PASS app surface Android checks: "+checks);
            finish(Activity.RESULT_OK,result);
        } catch(Throwable failure) {
            result.putString("error",failure.toString());
            finish(Activity.RESULT_CANCELED,result);
        }
    }
}
