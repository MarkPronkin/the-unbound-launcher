package dev.beampro.bridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Display-management decisions, independent of Android and exercised by host tests. */
public final class ManagedDisplayPolicy implements AutoCloseable {
    public static final class Mode {
        public final int width, height;
        public final float hz;
        public Mode(int width, int height, float hz) { this.width=width; this.height=height; this.hz=hz; }
        public boolean same(Mode other) {
            return other != null && width==other.width && height==other.height && Math.abs(hz-other.hz)<0.1f;
        }
    }
    public static final class Screen {
        public final int id;
        public final String uniqueId;
        public final boolean xreal;
        public final Mode active;
        public final List<Mode> modes;
        public Screen(int id,String uniqueId,boolean xreal,Mode active,List<Mode> modes) {
            this.id=id;this.uniqueId=uniqueId;this.xreal=xreal;this.active=active;this.modes=modes;
        }
        boolean eligible() { return id>0 && xreal && uniqueId!=null && uniqueId.startsWith("local:"); }
        Mode stereo() {
            Mode result=null;
            // The measured SBS EDID replaces 1920x1080 with 3840x1080. Do not
            // infer a hardware SBS switch from a mixed/ambiguous mode list.
            for(Mode mode:modes)if(mode.width==1920&&mode.height==1080)return null;
            for (Mode mode:modes) if (mode.width==3840 && mode.height==1080 && Math.abs(mode.hz-60)<0.5f
                    && (result==null || Math.abs(mode.hz-60)<Math.abs(result.hz-60))) result=mode;
            return result;
        }
    }
    public interface Platform {
        List<Screen> screens() throws Exception;
        boolean unlockedAndCool() throws Exception;
        boolean desktop(Screen screen) throws Exception;
        default boolean enableWithoutDesktopPreference() { return false; }
        void enable(Screen screen) throws Exception;
        String mirror() throws Exception;
        void mirror(String value) throws Exception;
        Mode preferred(Screen screen) throws Exception;
        void temporaryMode(Screen screen,Mode value) throws Exception;
        boolean limitExcludes(Screen screen,Mode mode) throws Exception;
        boolean respectsAppModes() throws Exception;
        void respectsAppModes(boolean value) throws Exception;
        void log(String message);
    }
    public interface Journal {
        Properties load() throws Exception;
        void save(Properties state) throws Exception;
    }
    private static final class Owned {
        final Screen screen;
        final Mode previous,requested;
        long since;
        boolean ready;
        Owned(Screen screen,Mode previous,Mode requested) {this.screen=screen;this.previous=previous;this.requested=requested;}
    }
    private final Platform platform;
    private Journal journal;
    private final Map<Integer,Owned> modes=new HashMap<>();
    private final Set<Integer> enabled=new HashSet<>();
    private boolean mirrorOwned,respectOwned;
    private String previousMirror;
    private boolean previousRespect;
    private long absentSince=-1;

    public ManagedDisplayPolicy(Platform platform) { this.platform=platform; }
    public ManagedDisplayPolicy(Platform platform,Journal journal) throws Exception {
        this.platform=platform;this.journal=journal;
        Properties state=journal.load();
        mirrorOwned=Boolean.parseBoolean(state.getProperty("mirror.owned","false"));
        previousMirror=state.getProperty("mirror.previous");
        respectOwned=Boolean.parseBoolean(state.getProperty("respect.owned","false"));
        previousRespect=Boolean.parseBoolean(state.getProperty("respect.previous","false"));
        for(String key:state.stringPropertyNames()) if(key.startsWith("mode.")&&key.endsWith(".unique")) {
            int id=Integer.parseInt(key.substring(5,key.length()-7));
            Mode previous=decode(state.getProperty("mode."+id+".previous"));
            Mode requested=decode(state.getProperty("mode."+id+".requested"));
            modes.put(id,new Owned(new Screen(id,state.getProperty(key),true,null,new ArrayList<Mode>()),previous,requested));
        }
        if(!state.isEmpty()) {platform.log("RECOVERING_PREVIOUS_DISPLAY_SESSION");close();}
    }
    private static String encode(Mode mode) {return mode==null?"none":mode.width+","+mode.height+","+mode.hz;}
    private static Mode decode(String value) {
        if(value==null||value.equals("none"))return null;
        String[] parts=value.split(",");return new Mode(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Float.parseFloat(parts[2]));
    }
    private void checkpoint() throws Exception {
        if(journal==null)return;
        Properties state=new Properties();
        if(mirrorOwned) {
            state.setProperty("mirror.owned","true");
            if(previousMirror!=null)state.setProperty("mirror.previous",previousMirror);
        }
        if(respectOwned) {
            state.setProperty("respect.owned","true");state.setProperty("respect.previous",Boolean.toString(previousRespect));
        }
        for(Map.Entry<Integer,Owned> entry:modes.entrySet()) {
            String prefix="mode."+entry.getKey()+".";Owned owned=entry.getValue();
            state.setProperty(prefix+"unique",owned.screen.uniqueId);
            state.setProperty(prefix+"previous",encode(owned.previous));
            state.setProperty(prefix+"requested",encode(owned.requested));
        }
        journal.save(state);
    }

    public void tick(long now) throws Exception {
        List<Screen> all=platform.screens(),screens=new ArrayList<>();
        for (Screen screen:all) if (screen.eligible()) screens.add(screen);
        Set<Integer> live=new HashSet<>();
        for (Screen screen:screens) live.add(screen.id);
        enabled.retainAll(live);
        if (screens.isEmpty() || !platform.unlockedAndCool()) {
            restoreModes(all);
            enabled.clear();
            if (absentSince<0) absentSince=now;
            if (!screens.isEmpty() || now-absentSince>=2000) restoreMirror();
            return;
        }
        absentSince=-1;
        Set<Integer> desktops=new HashSet<>();
        for (Screen screen:screens) if(platform.desktop(screen))desktops.add(screen.id);
        if (!desktops.isEmpty() && "1".equals(platform.mirror())) {
            if (!mirrorOwned) { previousMirror=platform.mirror(); mirrorOwned=true; }
            checkpoint();
            platform.mirror("0");
            platform.log("MIRROR_DISABLED_FOR_XREAL");
        }
        boolean needsRespect=false;
        Set<Integer> stereoIds=new HashSet<>();
        // Logical IDs can change at the mono/SBS EDID reconnect. Keep the original
        // restoration state for the same physical glasses, never for a recycled ID.
        for(Integer id:new ArrayList<>(modes.keySet())) if(!live.contains(id)) {
            Owned old=modes.get(id);
            for(Screen screen:screens) if(screen.uniqueId.equals(old.screen.uniqueId)&&screen.stereo()!=null) {
                Owned replacement=new Owned(screen,old.previous,screen.stereo());replacement.since=now;
                modes.remove(id);modes.put(screen.id,replacement);checkpoint();break;
            }
        }
        for (Screen screen:screens) {
            Mode target=screen.stereo();
            if (target==null) continue;
            stereoIds.add(screen.id);
            Owned owned=modes.get(screen.id);
            boolean nativeSize=screen.active!=null && screen.active.width==3840 && screen.active.height==1080;
            if (owned==null && nativeSize && !respectOwned) continue; // Preserve working SDK timing.
            if (owned==null) {
                owned=new Owned(screen,platform.preferred(screen),target);
                owned.since=now;
                modes.put(screen.id,owned); // Record BEFORE attempting a mutation.
                checkpoint();
                platform.temporaryMode(screen,target);
                platform.log("TEMP_STEREO_MODE display="+screen.id+" 3840x1080@"+target.hz);
            } else if(!target.same(platform.preferred(screen))) {
                platform.temporaryMode(screen,target);
            }
            if(nativeSize)owned.ready=true;
            else {
                if(owned.ready){owned.ready=false;owned.since=now;}
                if(now-owned.since>=10000)throw new IllegalStateException("XREAL hardware did not accept native stereo output");
            }
            needsRespect |= platform.limitExcludes(screen,target);
        }
        // The app/user mode votes are below the Pixel's LIMIT_MODE vote. This global
        // testing API is used only while a known XREAL stereo display needs that exception.
        if (needsRespect && !respectOwned) {
            previousRespect=platform.respectsAppModes();
            respectOwned=true;
            checkpoint();
            if (!previousRespect) platform.respectsAppModes(true);
            platform.log("STEREO_POLICY_OVERRIDE active");
        } else if (!needsRespect) restoreRespect();
        for (Integer id:new ArrayList<>(modes.keySet())) if (!stereoIds.contains(id)) restoreMode(id,screens);
        // Prepare the mode before enabling a newly connected display so AR can start at
        // native geometry, rather than constructing its Presentation at VGA dimensions.
        // Android 16 (including OxygenOS) has connected-display consent but no
        // per-display Desktop preference API. Its Cast button simply enables the
        // pending display. Apply that same operation only to identified XREALs;
        // leave the phone's existing content mode and resolution policy intact.
        for (Screen screen:screens) if (!enabled.contains(screen.id)
                && (desktops.contains(screen.id) || platform.enableWithoutDesktopPreference())) {
            platform.enable(screen);
            enabled.add(screen.id);
        }
    }

    private void restoreMode(int id,List<Screen> screens) throws Exception {
        Owned owned=modes.get(id);
        if (owned==null) return;
        for (Screen screen:screens) if (screen.id==id && owned.screen.uniqueId.equals(screen.uniqueId)
                && owned.requested.same(platform.preferred(screen))) platform.temporaryMode(screen,owned.previous);
        modes.remove(id);
        checkpoint();
    }
    private void restoreModes(List<Screen> screens) throws Exception {
        Exception failure=null;
        for (Integer id:new ArrayList<>(modes.keySet())) try {restoreMode(id,screens);} catch(Exception e){failure=e;}
        try {restoreRespect();} catch(Exception e){failure=e;}
        if(failure!=null)throw failure;
    }
    private void restoreRespect() throws Exception {
        if (!respectOwned) return;
        if (platform.respectsAppModes()!=previousRespect) platform.respectsAppModes(previousRespect);
        respectOwned=false;
        checkpoint();
        platform.log("STEREO_POLICY_OVERRIDE restored");
    }
    private void restoreMirror() throws Exception {
        if (!mirrorOwned) return;
        if ("0".equals(platform.mirror())) platform.mirror(previousMirror);
        mirrorOwned=false;
        checkpoint();
        platform.log("MIRROR_POLICY restored");
    }
    @Override public void close() throws Exception {
        Exception failure=null;
        try {restoreModes(platform.screens());} catch(Exception e){failure=e;}
        // A failed display snapshot must not prevent restoration of global policy.
        try {restoreRespect();} catch(Exception e){failure=e;}
        try {restoreMirror();} catch(Exception e){failure=e;}
        if(failure!=null)throw failure;
    }

    /** Independent watchdog lifetime: loss of its parent's pipe or heartbeat restores state. */
    public void runLease(BlockingQueue<String> commands,long timeoutMillis) throws Exception {
        long last=System.nanoTime()/1000000;
        try {
            while(true) {
                long now=System.nanoTime()/1000000;
                long remaining=timeoutMillis-(now-last);
                if(remaining<=0)break;
                String command=commands.poll(Math.min(500,remaining),TimeUnit.MILLISECONDS);
                now=System.nanoTime()/1000000;
                if("STOP".equals(command))break;
                if(command!=null) {
                    if(!command.equals("PING")&&!command.equals("WAKE"))throw new IllegalArgumentException("Invalid display watchdog command");
                    last=now;
                }
                if(now-last>=timeoutMillis)break;
                tick(now);
            }
        } finally {close();}
    }

    public static boolean limitExcludes(String dump,int display,Mode desired) {
        boolean votes=false,target=false;
        Pattern header=Pattern.compile("-?\\d+:");
        Pattern size=Pattern.compile("mWidth=(\\d+),\\s*mHeight=(\\d+)");
        for(String line:dump.split("\\n")) {
            String trimmed=line.trim();
            if(trimmed.equals("mVotesByDisplay:")){votes=true;continue;}
            if(!votes)continue;
            if(header.matcher(trimmed).matches()){target=trimmed.equals(display+":");continue;}
            if(target && line.contains("PRIORITY_LIMIT_MODE")) {
                Matcher match=size.matcher(line);
                return match.find() && (desired.width>Integer.parseInt(match.group(1))
                        || desired.height>Integer.parseInt(match.group(2)));
            }
        }
        return false;
    }
}
