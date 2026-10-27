package dev.beampro.port;

import java.lang.reflect.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
/** Run with app_process and the candidate APK DEXes on an isolated Android emulator.
 * Exercises the real SDK cleanup body, including its reuse of the incoming register.
 */
public final class PingCancellationDeviceChecks {
 public static void main(String[] args)throws Exception {
  try {
   if(android.os.Looper.getMainLooper()==null)android.os.Looper.prepareMainLooper();
   Class<?> type=Class.forName("com.xreal.glasses.servicesdk.helper.PingGSeriesHelper");
   Object helper=type.getConstructor().newInstance();
   Class<?> api=Class.forName("com.xreal.glasses.servicesdk.helper.PingGSeriesHelper$PingCallback");
   Class<?> guard=Class.forName("dev.beampro.port.PingCallbackCompat");
   AtomicInteger calls=new AtomicInteger();CountDownLatch done=new CountDownLatch(1);
   Object callback=Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(p,m,a)->{
    if(m.getName().equals("onPingResult")){if((Boolean)a[0])throw new AssertionError("Cancellation succeeded");
     if(!Thread.currentThread().getName().equals("ping_sdk_glasses_thread"))throw new AssertionError("Wrong callback thread");calls.incrementAndGet();done.countDown();}return null;});
   Object wrapped=guard.getMethod("wrap",Object.class,Object.class).invoke(null,helper,callback);
   java.lang.reflect.Field hf=type.getDeclaredField("mSdkHandler");hf.setAccessible(true);
   android.os.Handler h=(android.os.Handler)hf.get(helper);System.out.println("SDK worker: "+h.getLooper().getThread());
   h.post(()->System.out.println("SDK worker runs posted tasks"));
   Method release=type.getDeclaredMethod("onRelease");release.setAccessible(true);release.invoke(helper);System.out.println("Original SDK release returned");
   if(!done.await(10,TimeUnit.SECONDS)||calls.get()!=1)throw new AssertionError("Patched SDK release did not complete");
   api.getMethod("onPingResult",boolean.class).invoke(wrapped,true);release.invoke(helper);
   Thread.sleep(100);if(calls.get()!=1)throw new AssertionError("Duplicate callback");
   System.out.println("PASS actual Android SDK DEX cancellation, worker identity and late callback rejection");System.exit(0);
  }catch(Throwable error){error.printStackTrace();System.exit(1);}
 }
}