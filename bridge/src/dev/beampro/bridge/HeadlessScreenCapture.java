package dev.beampro.bridge;

import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.IBinder;
import java.lang.reflect.Method;

/** Capture API discovery for a shell helper without app-bound framework caches. */
final class HeadlessScreenCapture {
    static final class Screen {
        final int id,width,height;
        final String uniqueId;
        Screen(int id,int width,int height,String uniqueId) {
            this.id=id;this.width=width;this.height=height;this.uniqueId=uniqueId;
        }
        boolean same(Screen other) {return other!=null&&id==other.id&&uniqueId.equals(other.uniqueId);}
    }

    private final Object displays,windows;
    private final Method displayInfo,capture,frameScale,build,newListener,getBuffer;
    private final java.lang.reflect.Constructor<?> newBuilder;

    HeadlessScreenCapture() throws Exception {
        Class<?> displayApi=Class.forName("android.hardware.display.IDisplayManager");
        Class<?> windowApi=Class.forName("android.view.IWindowManager");
        displays=service("display",displayApi);windows=service("window",windowApi);
        displayInfo=displayApi.getMethod("getDisplayInfo",int.class);
        Method selected=null;
        for(Method method:windowApi.getMethods()) {
            Class<?>[] params=method.getParameterTypes();
            if(method.getName().equals("captureDisplay")&&params.length==3&&params[0]==int.class
                    &&params[1].getSimpleName().equals("CaptureArgs")
                    &&params[2].getSimpleName().equals("ScreenCaptureListener")) {
                if(selected!=null)throw new NoSuchMethodException("Ambiguous window capture API");
                selected=method;
            }
        }
        if(selected==null)throw new NoSuchMethodException("Window capture API unavailable");
        capture=selected;
        // Android 17 moved these types to ScreenCaptureInternal. Resolve the
        // types actually accepted by Binder, including on OEM framework builds.
        Class<?> args=capture.getParameterTypes()[1],root=args.getDeclaringClass();
        Class<?> builder=Class.forName(args.getName()+"$Builder");
        newBuilder=builder.getConstructor();frameScale=builder.getMethod("setFrameScale",float.class);
        build=builder.getMethod("build");newListener=root.getMethod("createSyncCaptureListener");
        if(!args.isAssignableFrom(build.getReturnType())
                ||!capture.getParameterTypes()[2].isAssignableFrom(newListener.getReturnType()))
            throw new NoSuchMethodException("Incompatible window capture API");
        // Use the public declared listener type, not its private anonymous implementation.
        getBuffer=newListener.getReturnType().getMethod("getBuffer");
        System.out.println("BEAM_RECENT_CAPTURE_API "+root.getName());
    }

    private static Object service(String name,Class<?> api) throws Exception {
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,name);
        if(binder==null)throw new IllegalStateException("Preview service unavailable: "+name);
        return Class.forName(api.getName()+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
    }

    Screen readVirtualDisplay(int id) throws Exception {
        if(id<=0)return null;
        Object info=displayInfo.invoke(displays,id);
        if(info==null)return null;
        Class<?> type=info.getClass();
        String name=(String)type.getField("name").get(info),unique=(String)type.getField("uniqueId").get(info);
        int width=type.getField("logicalWidth").getInt(info),height=type.getField("logicalHeight").getInt(info);
        if(type.getField("type").getInt(info)!=5||name==null||!name.startsWith("com.xreal.virtualdisplay-")
                ||unique==null||unique.isEmpty()||width<=0||height<=0)return null;
        return new Screen(id,width,height,unique);
    }

    /** Returns a software bitmap owned by the caller; closes all capture handles. */
    Bitmap capture(Screen screen) throws Exception {
        Bitmap hardwareBitmap=null;HardwareBuffer buffer=null;
        try {
            Object builder=newBuilder.newInstance();
            frameScale.invoke(builder,Math.min(1f,400f/Math.max(screen.width,screen.height)));
            // Retain the platform defaults excluding secure/protected content.
            Object args=build.invoke(builder),listener=newListener.invoke(null);
            capture.invoke(windows,screen.id,args,listener);
            Object result=getBuffer.invoke(listener);
            if(result==null)return null;
            Class<?> resultType=getBuffer.getReturnType();
            buffer=(HardwareBuffer)resultType.getMethod("getHardwareBuffer").invoke(result);
            if(buffer==null)return null;
            if((Boolean)resultType.getMethod("containsSecureLayers").invoke(result))return null;
            hardwareBitmap=(Bitmap)resultType.getMethod("asBitmap").invoke(result);
            return hardwareBitmap==null?null:hardwareBitmap.copy(Bitmap.Config.ARGB_8888,false);
        } finally {
            if(hardwareBitmap!=null)hardwareBitmap.recycle();
            if(buffer!=null)buffer.close();
        }
    }
}
