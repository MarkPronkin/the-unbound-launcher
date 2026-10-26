package dev.beampro.bridge;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Display;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;

/** Embedded-helper-only automatic XREAL routing and leased stereo mode management. */
final class ManagedDisplay {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private Process process;
    private OutputStreamWriter commands;
    private long started;
    private final Runnable heartbeat=this::pulse;

    static void start(Context shell) {
        ManagedDisplay manager=new ManagedDisplay();
        DisplayManager displays=shell.getSystemService(DisplayManager.class);
        DisplayManager.DisplayListener listener=new DisplayManager.DisplayListener() {
            public void onDisplayAdded(int id){manager.wake(id);}
            public void onDisplayRemoved(int id){manager.wake(id);}
            public void onDisplayChanged(int id){manager.wake(id);}
            // These are default interface callbacks on Android 17. The extra methods
            // remain harmless when compiled against the older public SDK.
            public void onDisplayConnected(int id){manager.wake(id);}
            public void onDisplayDisconnected(int id){manager.wake(id);}
        };
        try {
            DisplayManager.class.getMethod("registerDisplayListener",DisplayManager.DisplayListener.class,
                    Handler.class,long.class,long.class).invoke(displays,listener,manager.handler,7L,2L);
        } catch(Exception olderApi) {
            try { displays.registerDisplayListener(listener,manager.handler); }
            catch(RuntimeException unavailable) {
                // Callbacks are an optimization; the independent watchdog polls the service.
                System.err.println("BEAM_DISPLAY_CALLBACKS_UNAVAILABLE "+unavailable);
            }
        }
        manager.pulse();
    }
    private void wake(int id) { if(id!=0)handler.post(()->send("WAKE")); }
    private void send(String command) {
        if(commands==null)return;
        try{commands.write(command+"\n");commands.flush();}
        catch(Exception failure){commands=null;System.err.println("BEAM_DISPLAY_PIPE "+failure);}
    }
    private void pulse() {
        try {
            if(process==null||!process.isAlive()) {
                if(commands!=null){try{commands.close();}catch(Exception ignored){}commands=null;}
                if(process!=null&&process.exitValue()!=0&&android.os.SystemClock.elapsedRealtime()-started<30000) {
                    handler.postDelayed(heartbeat,2000);
                    return;
                }
                String path=System.getenv("CLASSPATH");
                if(path==null||path.isEmpty())throw new IllegalStateException("Display watchdog classpath unavailable");
                ProcessBuilder builder=new ProcessBuilder("/system/bin/app_process","/",Watchdog.class.getName());
                builder.environment().put("CLASSPATH",path);
                builder.redirectError(ProcessBuilder.Redirect.INHERIT);
                builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                process=builder.start();
                started=android.os.SystemClock.elapsedRealtime();
                commands=new OutputStreamWriter(process.getOutputStream(),"UTF-8");
            }
            send("PING");
        } catch(Exception failure){System.err.println("BEAM_DISPLAY_START_FAILED "+failure);}
        handler.postDelayed(heartbeat,2000);
    }

    public static final class Watchdog {
        public static void main(String[] args) throws Exception {
            if(android.os.Process.myUid()!=2000)throw new SecurityException("Shell display helper only");
            Looper.prepareMainLooper();
            LinkedBlockingQueue<String> queue=new LinkedBlockingQueue<>(32);
            Thread reader=new Thread(()->{
                try {
                    BufferedReader input=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8));
                    String line;
                    while((line=input.readLine())!=null)queue.put(line);
                } catch(Exception ignored){}
                finally{queue.clear();queue.offer("STOP");}
            },"display-watchdog-pipe");
            reader.setDaemon(true);reader.start();
            AndroidPlatform platform=new AndroidPlatform();
            File directory=new File("/data/local/tmp/unbound-launcher");
            if(!directory.isDirectory()&&!directory.mkdirs())throw new IllegalStateException("Display recovery directory unavailable");
            int exit=0;
            try(RandomAccessFile lockFile=new RandomAccessFile(new File(directory,"display-policy.lock"),"rw")) {
                FileLock lock=null;
                long deadline=android.os.SystemClock.elapsedRealtime()+10000;
                while(lock==null&&android.os.SystemClock.elapsedRealtime()<deadline) {
                    lock=lockFile.getChannel().tryLock();
                    if(lock==null)Thread.sleep(50);
                }
                if(lock==null)throw new IllegalStateException("Previous display watchdog is still restoring state");
                try {
                    ManagedDisplayPolicy policy=new ManagedDisplayPolicy(platform,new FileJournal(new File(directory,"display-policy.properties")));
                    System.out.println("BEAM_DISPLAY_WATCHDOG_READY");System.out.flush();
                    policy.runLease(queue,8000);
                    System.out.println("BEAM_DISPLAY_WATCHDOG_RESTORED");System.out.flush();
                } finally {lock.release();}
            } catch(Throwable failure) {
                System.err.println("BEAM_DISPLAY_WATCHDOG_FAILED recovery journal retained: "+failure);
                failure.printStackTrace(System.err);
                exit=1;
            }
            System.exit(exit);
        }
    }

    private static final class FileJournal implements ManagedDisplayPolicy.Journal {
        final File file;
        FileJournal(File file){this.file=file;}
        public Properties load() throws Exception {
            Properties state=new Properties();
            if(file.isFile())try(FileInputStream input=new FileInputStream(file)){state.load(input);}
            return state;
        }
        public void save(Properties state) throws Exception {
            if(state.isEmpty()) {
                if(file.exists()&&!file.delete())throw new IllegalStateException("Cannot clear display recovery journal");
                return;
            }
            File temporary=new File(file.getPath()+".tmp");
            try(FileOutputStream output=new FileOutputStream(temporary)) {
                state.store(output,"Unbound temporary display policy recovery");output.getFD().sync();
            }
            if(!temporary.renameTo(file))throw new IllegalStateException("Cannot commit display recovery journal");
        }
    }

    private static final class AndroidPlatform implements ManagedDisplayPolicy.Platform {
        final Object service;
        final Class<?> api;
        Object windowService,thermalService;
        Class<?> windowApi,thermalApi;
        final boolean temporaryModes,legacyEnable,desktopPreference,connectedEnable,respectModes;
        final Map<Integer,String> limitDumps=new HashMap<>();
        final Map<Integer,String> observedModes=new HashMap<>();
        final java.util.Set<String> configured=new java.util.HashSet<>();
        AndroidPlatform() throws Exception {
            api=Class.forName("android.hardware.display.IDisplayManager");
            service=connectService("display",api);
            // OEM frameworks can backport individual APIs. Probe complete operations
            // independently; a getter alone is not evidence that its setter exists.
            temporaryModes=has("setUserPreferredDisplayMode",int.class,Display.Mode.class,boolean.class)
                    &&has("getUserPreferredDisplayMode",int.class);
            desktopPreference=has("getConnectionPreference",String.class)
                    &&has("setConnectionPreference",String.class,int.class);
            connectedEnable=has("enableConnectedDisplay",int.class);
            respectModes=has("shouldAlwaysRespectAppRequestedMode")
                    &&has("setShouldAlwaysRespectAppRequestedMode",boolean.class);
            legacyEnable=connectedEnable&&!desktopPreference;
            log("CAPABILITIES desktop="+desktopPreference+" enable="+connectedEnable
                    +" temporaryModes="+temporaryModes+" appModePolicy="+respectModes);
        }
        private boolean has(String name,Class<?>... parameters) {
            try { api.getMethod(name,parameters);return true; }
            catch(NoSuchMethodException absent) { return false; }
        }
        static Object connectService(String name,Class<?> contract) throws Exception {
            IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,name);
            if(binder==null)throw new IllegalStateException("Display helper service unavailable: "+name);
            return Class.forName(contract.getName()+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        }
        Object call(String method,Class<?>[] types,Object... args) throws Exception {return api.getMethod(method,types).invoke(service,args);}
        static ManagedDisplayPolicy.Mode mode(Display.Mode mode) {
            return mode==null?null:new ManagedDisplayPolicy.Mode(mode.getPhysicalWidth(),mode.getPhysicalHeight(),mode.getRefreshRate());
        }
        public List<ManagedDisplayPolicy.Screen> screens() throws Exception {
            List<ManagedDisplayPolicy.Screen> result=DisplaySnapshotReader.read(service,api,temporaryModes);
            java.util.Set<Integer> live=new java.util.HashSet<>();
            for(ManagedDisplayPolicy.Screen screen:result) {
                live.add(screen.id);
                ManagedDisplayPolicy.Mode current=screen.active;
                String state=(current==null?"none":current.width+"x"+current.height+"@"+current.hz)
                        +" xreal="+screen.xreal+" unique="+screen.uniqueId;
                if(!state.equals(observedModes.put(screen.id,state)))log("STATE display="+screen.id+" active="+state);
            }
            limitDumps.keySet().retainAll(live);
            observedModes.keySet().retainAll(live);
            return result;
        }
        public boolean unlockedAndCool() throws Exception {
            // KeyguardManager initializes WindowManagerGlobal's animation cache.
            // Android 17 requires app-bound ApplicationSharedMemory there, which
            // a shell app_process does not have. Query the same Binder APIs directly.
            if(windowService==null) {
                windowApi=Class.forName("android.view.IWindowManager");
                windowService=connectService("window",windowApi);
            }
            if((Boolean)windowApi.getMethod("isKeyguardLocked").invoke(windowService))return false;
            if(thermalService==null) {
                thermalApi=Class.forName("android.os.IThermalService");
                thermalService=connectService("thermalservice",thermalApi);
            }
            return (Integer)thermalApi.getMethod("getCurrentThermalStatus").invoke(thermalService)
                    <PowerManager.THERMAL_STATUS_SEVERE;
        }
        public boolean desktop(ManagedDisplayPolicy.Screen screen) throws Exception {
            if(!desktopPreference)return false;
            try {
                int preference=(Integer)call("getConnectionPreference",new Class<?>[]{String.class},screen.uniqueId);
                if(preference!=1)call("setConnectionPreference",new Class<?>[]{String.class,int.class},screen.uniqueId,1);
                boolean accepted=(Integer)call("getConnectionPreference",new Class<?>[]{String.class},screen.uniqueId)==1;
                if(accepted&&configured.add(screen.uniqueId))log("AUTO_DESKTOP configured for "+screen.uniqueId);
                return accepted;
            } catch(NoSuchMethodException olderApi){return false;}
        }
        public void enable(ManagedDisplayPolicy.Screen screen) throws Exception {
            if(!connectedEnable)return; // Older frameworks already expose active displays.
            call("enableConnectedDisplay",new Class<?>[]{int.class},screen.id);
            log("AUTO_ENABLED display="+screen.id+" unique="+screen.uniqueId);
        }
        public boolean enableWithoutDesktopPreference() { return legacyEnable; }
        public String mirror() throws Exception {
            // Use Android's shell settings client, which acquires its provider as
            // an external shell caller instead of requiring an app-bound resolver.
            ShellCommand.Result result=ShellCommand.run(3000,"settings","get","secure","mirror_built_in_display");
            if(!result.succeeded())throw new IllegalStateException("Cannot read Desktop content mode: "+result.output);
            String value=result.output.trim();
            if(value.equals("null"))return null;
            if(!value.equals("0")&&!value.equals("1"))throw new IllegalStateException("Unexpected mirror setting: "+value);
            return value;
        }
        public void mirror(String value) throws Exception {
            if(value!=null&&!value.equals("0")&&!value.equals("1"))throw new IllegalArgumentException("Invalid mirror setting");
            ShellCommand.Result result=value==null
                    ?ShellCommand.run(3000,"settings","delete","secure","mirror_built_in_display")
                    :ShellCommand.run(3000,"settings","put","secure","mirror_built_in_display",value);
            if(!result.succeeded()||!java.util.Objects.equals(value,mirror()))
                throw new IllegalStateException("Cannot select Desktop content mode: "+result.output);
        }
        public ManagedDisplayPolicy.Mode preferred(ManagedDisplayPolicy.Screen screen) throws Exception {
            return mode((Display.Mode)call("getUserPreferredDisplayMode",new Class<?>[]{int.class},screen.id));
        }
        public void temporaryMode(ManagedDisplayPolicy.Screen screen,ManagedDisplayPolicy.Mode value) throws Exception {
            Display.Mode target=value==null?null:Display.Mode.class.getConstructor(int.class,int.class,float.class)
                    .newInstance(value.width,value.height,value.hz);
            // The third argument is essential: never persist resolution/refresh preferences.
            call("setUserPreferredDisplayMode",new Class<?>[]{int.class,Display.Mode.class,boolean.class},screen.id,target,false);
        }
        public boolean limitExcludes(ManagedDisplayPolicy.Screen screen,ManagedDisplayPolicy.Mode target) throws Exception {
            if(!respectModes)return false; // Keep the window/mode request when no reversible override exists.
            String dump=limitDumps.get(screen.id);
            // A newly connected disabled display may not have its LIMIT_MODE vote
            // until it is enabled. Never pin that initial "no vote yet" result while
            // the hardware is still at the wrong size.
            if(dump!=null&&!ManagedDisplayPolicy.limitExcludes(dump,screen.id,target)
                    &&(screen.active==null||screen.active.width!=target.width||screen.active.height!=target.height))dump=null;
            if(dump==null) {
                ShellCommand.Result result=ShellCommand.run(3000,"sh","-c","dumpsys display | sed -n '/mVotesByDisplay:/,$p'");
                if(result.timedOut||result.exitCode!=0||!result.output.contains("mVotesByDisplay:"))
                    throw new IllegalStateException("Cannot read external display mode policy");
                dump=result.output;limitDumps.put(screen.id,dump);
            }
            return ManagedDisplayPolicy.limitExcludes(dump,screen.id,target);
        }
        public boolean respectsAppModes() throws Exception {
            return (Boolean)call("shouldAlwaysRespectAppRequestedMode",new Class<?>[]{});
        }
        public void respectsAppModes(boolean value) throws Exception {
            call("setShouldAlwaysRespectAppRequestedMode",new Class<?>[]{boolean.class},value);
        }
        public void log(String message){System.out.println("BEAM_DISPLAY_"+message);System.out.flush();}
    }
}
