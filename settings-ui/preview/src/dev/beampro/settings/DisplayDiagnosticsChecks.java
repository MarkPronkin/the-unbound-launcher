package dev.beampro.settings;

import android.app.Instrumentation;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import dev.beampro.bridge.DisplayDumpPipe;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/** Separate-process Binder and real Android pipe checks using the production client and exporter. */
public final class DisplayDiagnosticsChecks extends Instrumentation {
    private int passed;
    private Uri fixture;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            fixture=Uri.parse("content://"+getTargetContext().getPackageName()+".unbound.bridge");
            mode("success");
            Bundle connection=getTargetContext().getContentResolver().call(fixture,"get",null,null);
            require(!(connection.getBinder("binder") instanceof Binder),"fixture runs across an actual process boundary");
            String[] dumps=DebugDisplay.capture(getTargetContext());
            require(dumps[0].equals(payload(0))&&dumps[1].equals(payload(1)),"large display and SurfaceFlinger payloads cross Binder by descriptor intact: "+dumps[0].substring(0,Math.min(250,dumps[0].length()))+" / "+dumps[1].substring(0,Math.min(250,dumps[1].length())));
            require(dumps[0].getBytes(StandardCharsets.UTF_8).length>500_000,"test exceeds the safe Binder string budget");
            try(ParcelFileDescriptor ignored=new DisplayDumpPipe(android.os.Process.myUid()).open(android.os.Process.myUid()+1,0)) {
                throw new AssertionError("accepted another UID");
            } catch(SecurityException expected){passed++;}
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();
            try {
                data.writeInterfaceToken(DisplayDiagnostics.TOKEN);data.writeInt(99);
                connection.getBinder("binder").transact(DisplayDiagnostics.TRANSACTION,data,reply,0);
                try{reply.readException();throw new AssertionError("accepted arbitrary service selector");}
                catch(IllegalArgumentException expected){passed++;}
            }finally{data.recycle();reply.recycle();}
            DebugData debug=DebugData.get(getTargetContext());debug.setEnabled(true);
            File file;
            try{file=debug.files.submit(debug::export).get(30,TimeUnit.SECONDS);}
            finally{debug.setEnabled(false);}
            try(ZipFile zip=new ZipFile(file)){
                require(zip.size()==5,"production export contains metadata, logs, network history and both dumps");
                require(HelpPageChecks.read(zip.getInputStream(zip.getEntry("display.txt"))).equals(payload(0)),"production ZIP preserves display dump");
                require(HelpPageChecks.read(zip.getInputStream(zip.getEntry("surfaceflinger.txt"))).equals(payload(1)),"production ZIP preserves SurfaceFlinger dump");
                require(file.length()<=15_000_000,"production ZIP fits hard cap");
            }
            mode("oversize");dumps=DebugDisplay.capture(getTargetContext());
            for(String dump:dumps){
                require(dump.getBytes(StandardCharsets.UTF_8).length<=DisplayDiagnostics.MAX_BYTES&&dump.contains("truncated"),"pipe producer limits oversized output explicitly");
                require(!dump.contains("\ufffd"),"pipe truncation retains valid UTF-8");
            }
            mode("denied");dumps=DebugDisplay.capture(getTargetContext());
            require(dumps[0].contains("Permission Denial")&&dumps[1].contains("Permission Denial"),"permission errors remain diagnostic evidence");
            mode("missing");dumps=DebugDisplay.capture(getTargetContext());
            require(dumps[0].contains("not running")&&dumps[1].contains("not running"),"missing helper yields explanations for both dumps");
            mode("old");dumps=DebugDisplay.capture(getTargetContext());
            require(dumps[0].contains("does not support")&&dumps[1].contains("restart"),"older helper does not prevent export");
            mode("empty");dumps=DebugDisplay.capture(getTargetContext());
            require(dumps[0].contains("empty diagnostic pipe")&&dumps[1].contains("empty diagnostic pipe"),"empty pipe is reported as a failure");
            mode("blocked");long before=SystemClock.uptimeMillis();dumps=DebugDisplay.capture(getTargetContext(),100);
            require(dumps[0].contains("TimeoutException")&&SystemClock.uptimeMillis()-before<2000,"blocked Binder cannot hold export indefinitely");
            require(dumps[1].contains("RejectedExecutionException"),"blocked Binder does not accumulate requests");
            mode("release");SystemClock.sleep(150);mode("success");
            dumps=DebugDisplay.capture(getTargetContext());
            require(dumps[0].equals(payload(0))&&dumps[1].equals(payload(1)),"late Binder result releases resources and later capture recovers");
            mode("slowpipe");before=SystemClock.uptimeMillis();dumps=DebugDisplay.capture(getTargetContext(),100);
            require(dumps[0].contains("TimeoutException")&&SystemClock.uptimeMillis()-before<2000,"stalled pipe read has a bounded client deadline");
            SystemClock.sleep(900);mode("success");dumps=DebugDisplay.capture(getTargetContext());
            require(dumps[0].equals(payload(0)),"closing timed-out pipe releases producer and client worker");
            result.putString("stream","Display diagnostic runtime checks passed: "+passed+"\n");finish(-1,result);
        }catch(Throwable failure){
            android.util.Log.e("DisplayDiagnosticsChecks","Failed after "+passed,failure);
            result.putString("stream","Display diagnostic checks failed after "+passed+": "+failure+"\n");finish(0,result);
        }finally{if(fixture!=null)mode("missing");}
    }
    private void mode(String value){getTargetContext().getContentResolver().call(fixture,"fixture",value,null);}
    private void require(boolean value,String message){if(!value)throw new AssertionError(message);passed++;}
    private static String payload(int kind){return "Captured: fixture\nIdentity: ADB shell helper\nService: "+DisplayDiagnostics.service(kind)+"\n"+new String(new char[300_000]).replace('\0','\u20ac');}

    public static final class FixtureProvider extends ContentProvider {
        private volatile String mode="missing";
        private volatile CountDownLatch release=new CountDownLatch(1);
        private final DisplayDumpPipe dumps=new DisplayDumpPipe(android.os.Process.myUid(),kind->{
            if(mode.equals("slowpipe"))SystemClock.sleep(700);
            if(mode.equals("empty"))return "";
            if(mode.equals("denied"))return "exit=1; timedOut=false\nPermission Denial: cannot dump "+DisplayDiagnostics.service(kind);
            if(mode.equals("oversize"))return new String(new char[700_000]).replace('\0','\u20ac');
            return payload(kind);
        });
        private final IBinder binder=new Binder(){
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws android.os.RemoteException{
                if(code!=DisplayDiagnostics.TRANSACTION||mode.equals("old"))return false;
                data.enforceInterface(DisplayDiagnostics.TOKEN);
                if(mode.equals("blocked")){
                    try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                    return false;
                }
                try(ParcelFileDescriptor pipe=dumps.open(Binder.getCallingUid(),data.readInt())){
                    reply.writeNoException();pipe.writeToParcel(reply,0);return true;
                }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
            }
        };
        public boolean onCreate(){return true;}
        @Override public Bundle call(String method,String arg,Bundle extras){
            if(method.equals("fixture")){
                if(arg.equals("release"))release.countDown();
                if(arg.equals("blocked"))release=new CountDownLatch(1);
                mode=arg;return new Bundle();
            }
            Bundle result=new Bundle();if(!mode.equals("missing"))result.putBinder("binder",binder);return result;
        }
        public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}
        public String getType(Uri u){return null;}
        public Uri insert(Uri u,ContentValues v){return null;}
        public int delete(Uri u,String s,String[] a){return 0;}
        public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
    }
}
