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
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Isolated cross-process checks for the production control client and pipe producer. */
public final class ControlDiagnosticsRuntimeChecks extends Instrumentation {
    private int checks;
    private Uri fixture;
    private void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private Bundle call(String method,String value){return getTargetContext().getContentResolver().call(fixture,method,value,null);}
    private void mode(String value){call("mode",value);}
    private void entered() {
        long until=SystemClock.uptimeMillis()+2000;
        while(SystemClock.uptimeMillis()<until){if(call("state",null).getBoolean("entered"))return;SystemClock.sleep(10);}
        throw new AssertionError("Blocked request did not enter fixture");
    }
    private void released() {
        call("mode","release");
        long until=SystemClock.uptimeMillis()+2000;
        while(SystemClock.uptimeMillis()<until){if(call("state",null).getBoolean("drained")){SystemClock.sleep(80);mode("success");return;}SystemClock.sleep(10);}
        throw new AssertionError("Blocked request did not drain");
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            fixture=Uri.parse("content://"+getTargetContext().getPackageName()+".unbound.bridge");mode("success");
            IBinder binder=call("get",null).getBinder("binder");
            check(!(binder instanceof Binder),"Control fixture crosses an actual process boundary");
            check(DisplayDiagnostics.GLASSES_CONTROL==3&&DisplayDiagnostics.MAX_CONTROL_BYTES==32000,"Stable control selector and byte budget");
            check(DebugDisplay.captureControl(getTargetContext()).equals(payload(3)),"Control capture crosses the real Binder descriptor pipe");
            check(DebugDisplay.captureLink(getTargetContext()).equals(payload(2)),"Original link capture remains available");
            DisplayDumpPipe direct=new DisplayDumpPipe(android.os.Process.myUid());
            for(int kind:new int[]{0,1,2,3})try(ParcelFileDescriptor ignored=direct.open(android.os.Process.myUid()+1,kind)){
                throw new AssertionError("Other UID accepted");
            }catch(SecurityException expected){checks++;}
            try(ParcelFileDescriptor ignored=direct.open(android.os.Process.myUid(),99)){throw new AssertionError("Unknown selector accepted");}
            catch(IllegalArgumentException expected){checks++;}

            mode("oversize");String control=DebugDisplay.captureControl(getTargetContext());
            check(control.getBytes(StandardCharsets.UTF_8).length<=32000&&control.contains("truncated")&&!control.contains("\ufffd"),"Control producer enforces UTF-8 byte budget");
            mode("denied");control=DebugDisplay.captureControl(getTargetContext());
            check(control.contains("Permission denied")&&control.contains("Glasses control channel"),"Permission errors remain readable");
            mode("oldcontrol");control=DebugDisplay.captureControl(getTargetContext());
            check(control.contains("Unknown display diagnostic")&&control.contains("restart"),"Older helper rejecting kind three gives an update explanation");
            check(DebugDisplay.captureLink(getTargetContext()).equals(payload(2)),"Pre-control helper still supplies link diagnostics");
            mode("missing");check(DebugDisplay.captureControl(getTargetContext()).contains("not running"),"Absent helper yields a readable control report");
            mode("empty");check(DebugDisplay.captureControl(getTargetContext()).contains("empty diagnostic pipe"),"Empty helper pipe is explained");

            mode("blockLinkProducer");AtomicReference<String> link=new AtomicReference<>();
            Thread linkThread=new Thread(()->link.set(DebugDisplay.captureLink(getTargetContext())));linkThread.start();entered();
            long start=SystemClock.uptimeMillis();control=DebugDisplay.captureControl(getTargetContext(),1200);
            check(control.equals(payload(3))&&SystemClock.uptimeMillis()-start<1200,"Control client and helper producer bypass a blocked link capture");
            released();linkThread.join(2000);check(!linkThread.isAlive()&&payload(2).equals(link.get()),"Link capture drains after independent control capture");

            mode("blockLinkBinder");linkThread=new Thread(()->link.set(DebugDisplay.captureLink(getTargetContext())));linkThread.start();entered();
            start=SystemClock.uptimeMillis();control=DebugDisplay.captureControl(getTargetContext(),1200);
            check(control.equals(payload(3))&&SystemClock.uptimeMillis()-start<1200,"Control IPC worker bypasses an outstanding display Binder transaction");
            released();linkThread.join(2000);check(!linkThread.isAlive(),"Blocked link Binder drains");

            mode("blockControlBinder");start=SystemClock.uptimeMillis();control=DebugDisplay.captureControl(getTargetContext(),100);entered();
            check(control.contains("TimeoutException")&&SystemClock.uptimeMillis()-start<1500,"Control Binder has a bounded client deadline");
            int requests=call("state",null).getInt("controlRequests");
            control=DebugDisplay.captureControl(getTargetContext(),100);
            check(control.contains("RejectedExecutionException")&&call("state",null).getInt("controlRequests")==requests,"Blocked control Binder cannot accumulate queued requests");
            check(DebugDisplay.captureLink(getTargetContext()).equals(payload(2)),"Blocked control IPC cannot block original link captures");
            released();check(DebugDisplay.captureControl(getTargetContext()).equals(payload(3)),"Control worker recovers after late Binder completion");

            mode("blockControlProducer");control=DebugDisplay.captureControl(getTargetContext(),100);entered();
            check(control.contains("TimeoutException"),"Stalled control pipe is bounded");
            check(DebugDisplay.captureLink(getTargetContext()).equals(payload(2)),"A blocked control producer does not hold the helper link slot");
            released();check(DebugDisplay.captureControl(getTargetContext()).equals(payload(3)),"Timed-out control pipe resources recover");
            result.putString("stream","PASS control diagnostic Android assertions: "+checks+"\n");finish(-1,result);
        }catch(Throwable failure){result.putString("stream","FAIL after "+checks+": "+android.util.Log.getStackTraceString(failure));finish(0,result);}
        finally{if(fixture!=null){call("mode","release");mode("missing");}}
    }
    private static String payload(int kind){return "Captured: fixture\nIdentity: ADB shell helper\nService: "+DisplayDiagnostics.service(kind)+"\n"+new String(new char[kind==3?2000:3000]).replace('\0','\u20ac');}

    public static final class FixtureProvider extends ContentProvider {
        private volatile String mode="missing";
        private volatile CountDownLatch release=new CountDownLatch(1),entered=new CountDownLatch(1),drained=new CountDownLatch(1);
        private final AtomicInteger controlRequests=new AtomicInteger();
        private void hold(){entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}finally{drained.countDown();}}
        private final DisplayDumpPipe dumps=new DisplayDumpPipe(android.os.Process.myUid(),kind->{
            String value=mode;
            if(value.equals("blockLinkProducer")&&kind==2||value.equals("blockControlProducer")&&kind==3)hold();
            if(value.equals("empty"))return "";
            if(value.equals("oversize"))return new String(new char[100000]).replace('\0','\u20ac');
            if(value.equals("denied"))return "Service: "+DisplayDiagnostics.service(kind)+"\nPermission denied\n";
            return payload(kind);
        });
        private final IBinder binder=new Binder(){
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws android.os.RemoteException {
                if(code!=DisplayDiagnostics.TRANSACTION)return false;
                data.enforceInterface(DisplayDiagnostics.TOKEN);int kind=data.readInt();
                if(kind==3)controlRequests.incrementAndGet();
                String value=mode;
                if(value.equals("oldcontrol")&&kind==3)throw new IllegalArgumentException("Unknown display diagnostic");
                if(value.equals("blockLinkBinder")&&kind==2||value.equals("blockControlBinder")&&kind==3){hold();return false;}
                try(ParcelFileDescriptor pipe=dumps.open(Binder.getCallingUid(),kind)){
                    reply.writeNoException();pipe.writeToParcel(reply,0);return true;
                }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
            }
        };
        public boolean onCreate(){return true;}
        @Override public Bundle call(String method,String value,Bundle extras){
            if(method.equals("mode")){
                if(value.equals("release"))release.countDown();
                else{mode=value;if(value.startsWith("block")){release=new CountDownLatch(1);entered=new CountDownLatch(1);drained=new CountDownLatch(1);}}
                return new Bundle();
            }
            Bundle result=new Bundle();
            if(method.equals("state")){result.putBoolean("entered",entered.getCount()==0);result.putBoolean("drained",drained.getCount()==0);result.putInt("controlRequests",controlRequests.get());}
            else if(!mode.equals("missing"))result.putBinder("binder",binder);
            return result;
        }
        public Cursor query(Uri u,String[] p,String s,String[] a,String o){return null;}
        public String getType(Uri u){return null;}
        public Uri insert(Uri u,ContentValues v){return null;}
        public int delete(Uri u,String s,String[] a){return 0;}
        public int update(Uri u,ContentValues v,String s,String[] a){return 0;}
    }

    /** Read-only shell probe; usable without installing this fixture on a physical phone. */
    public static final class ShellCapture {
        public static void main(String[] args) {
            if(android.os.Process.myUid()!=2000)throw new SecurityException("Run as shell");
            long start=SystemClock.uptimeMillis();byte[] report=DisplayDiagnostics.encode(3,DisplayDiagnostics.capture(3));
            System.out.println(new String(report,StandardCharsets.UTF_8));
            System.out.println("CONTROL_CAPTURE_BYTES="+report.length+"; elapsed_ms="+(SystemClock.uptimeMillis()-start));
        }
    }
}
