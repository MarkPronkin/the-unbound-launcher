package dev.beampro.settings;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;
import dev.beampro.settings.preview.SettingsPreviewActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;

/** Real phone UI, private export and Downloads flow, independent of physical glasses or Unity. */
public final class DisplayTestsChecks extends Instrumentation {
    private int checks;private Activity owner;private MenuActivity help,menu;private Uri download;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private View tagged(String tag){return menu.getWindow().getDecorView().findViewWithTag(tag);}
    private void idle(DisplayTests tests) {
        long until=SystemClock.uptimeMillis()+12_000;AtomicBoolean done=new AtomicBoolean();
        do{runOnMainSync(()->done.set(!tests.busy()&&!tests.saving()));if(done.get())return;SystemClock.sleep(25);}while(SystemClock.uptimeMillis()<until);
        throw new AssertionError("display test did not become idle");
    }
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            owner=startActivitySync(new Intent(getTargetContext(),SettingsPreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            DebugData data=DebugData.get(owner);runOnMainSync(()->data.setEnabled(false));
            getTargetContext().getContentResolver().call(Uri.parse("content://"+getTargetContext().getPackageName()+".unbound.bridge"),"fixture","success",null);
            help=MenuChecks.open(this,owner,"Help");
            ActivityMonitor opened=addMonitor(MenuActivity.class.getName(),null,false);
            try{runOnMainSync(()->help.getWindow().getDecorView().findViewWithTag("display-tests-open").performClick());menu=(MenuActivity)waitForMonitorWithTimeout(opened,5000);}
            finally{removeMonitor(opened);}
            check(menu!=null&&menu.page instanceof DisplayTestsPage,"Help opens the native Display tests page");waitForIdleSync();
            SystemClock.sleep(1000);waitForIdleSync(); // Let the existing Activity fade finish before visual QA.
            StringBuilder layout=new StringBuilder();
            runOnMainSync(()->{
                View navigation=tagged("display-tests-back");android.graphics.Rect rect=new android.graphics.Rect();
                for(View view=navigation;view!=null;view=view.getParent() instanceof View?(View)view.getParent():null){
                    int[] at=new int[2];view.getLocationOnScreen(at);layout.append(view.getClass().getSimpleName()).append(' ').append(at[0]).append(',').append(at[1])
                            .append(' ').append(view.getWidth()).append('x').append(view.getHeight()).append(" alpha=").append(view.getAlpha()).append(" visibility=").append(view.getVisibility()).append('\n');
                }
                check(navigation.getGlobalVisibleRect(rect)&&rect.width()>0&&rect.height()>0,"The page has a visible on-screen Back control");
            });
            try(FileOutputStream output=new FileOutputStream(new File(getTargetContext().getCacheDir(),"display-tests-layout.txt"))){output.write(layout.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            Bitmap phone=getUiAutomation().takeScreenshot();check(phone!=null,"The phone test page produces a reviewable screenshot");
            try(FileOutputStream output=new FileOutputStream(new File(getTargetContext().getCacheDir(),"display-tests-phone.png"))){phone.compress(Bitmap.CompressFormat.PNG,100,output);}phone.recycle();
            DisplayTests tests=DisplayTests.get(menu);
            runOnMainSync(()->{
                check(DisplayTests.active(),"The open test page suppresses automatic AR entry");
                check(tagged("display-tests-check").isEnabled()&&tagged("display-tests-2d").isEnabled()&&tagged("display-tests-sbs").isEnabled(),"Connection, 2D and SBS tests are discoverable");
                check(tagged("display-tests-restore")!=null,"Recovery is available on the phone");
                check(!data.enabled()&&!data.collecting(),"Opening display tests does not enable broad app log collection");
                tagged("display-tests-check").performClick();
            });
            idle(tests);runOnMainSync(()->check(!data.enabled()&&!data.collecting(),"Read-only capture leaves log collection preferences unchanged"));
            check(data.hasDisplayTests(),"Explicit test results are retained without logcat");
            runOnMainSync(()->tagged("display-tests-export").performClick());
            data.files.submit(()->{}).get(40,java.util.concurrent.TimeUnit.SECONDS);waitForIdleSync();
            File file=data.latestExport();check(file!=null,"The page creates a debug file");
            try(ZipFile zip=new ZipFile(file)){
                String history=HelpPageChecks.read(zip.getInputStream(zip.getEntry("display-tests.txt")));
                String capture=HelpPageChecks.read(zip.getInputStream(zip.getEntry("display-test-captures.txt")));
                check(history.contains("Connection check")&&history.contains("INITIAL"),"The report identifies the requested experiment and its initial display state");
                check(capture.contains("stage=before")&&capture.contains("Connected display startup"),"The real client retains the helper's connected display snapshot");
                check(file.length()<=15_000_000,"The final ZIP obeys its hard byte limit");
            }
            ActivityMonitor chooser=new ActivityMonitor(){@Override public ActivityResult onStartActivity(Intent intent){
                if(Intent.ACTION_CHOOSER.equals(intent.getAction())){
                    Intent send=intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    check(send!=null&&"application/zip".equals(send.getType())&&send.getParcelableExtra(Intent.EXTRA_STREAM)!=null,"Share uses the existing private ZIP provider");
                    return new ActivityResult(0,null);
                }return null;
            }};
            addMonitor(chooser);try{runOnMainSync(()->tagged("display-tests-share").performClick());}finally{removeMonitor(chooser);}
            runOnMainSync(()->tagged("display-tests-save").performClick());
            data.files.submit(()->{}).get(15,java.util.concurrent.TimeUnit.SECONDS);waitForIdleSync();
            try(Cursor cursor=getTargetContext().getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,new String[]{MediaStore.Downloads._ID},
                    MediaStore.Downloads.DISPLAY_NAME+"=?",new String[]{file.getName()},null)){
                check(cursor!=null&&cursor.moveToFirst(),"The test file can be saved to Downloads");
                download=android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cursor.getLong(0));
            }
            pattern(true);pattern(false);
            // The preview intentionally has no XREAL SDK. A control-test failure must leave the UI usable.
            runOnMainSync(()->tagged("display-tests-2d").performClick());idle(tests);
            runOnMainSync(()->{
                check(tagged("display-tests-check").isEnabled(),"Unavailable SDK initialization does not leave the test UI busy");
                check(tagged("display-tests-share")==null,"Starting a new test invalidates the previous share button");
                check(!data.enabled()&&!data.collecting(),"Native-test preparation does not silently enable app logs");
            });
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);MenuChecks.awaitClosed(this,menu);waitForIdleSync();
            check(!DisplayTests.active(),"Closing an idle test page releases automatic AR entry");
            result.putString("stream","PASS display test Android UI assertions: "+checks+"\n");finish(-1,result);
        }catch(Throwable failure){result.putString("stream","FAIL display test UI after "+checks+": "+android.util.Log.getStackTraceString(failure));finish(0,result);}
        finally{
            if(download!=null)getTargetContext().getContentResolver().delete(download,null,null);
            if(menu!=null)runOnMainSync(menu::finish);if(help!=null)runOnMainSync(help::finish);if(owner!=null)runOnMainSync(owner::finish);
        }
    }
    private void pattern(boolean stereo) throws Exception {
        int width=stereo?3840:1920,height=1080;
        ImageReader reader=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);
        AtomicReference<VirtualDisplay> display=new AtomicReference<>();AtomicReference<DisplayTestPattern> presentation=new AtomicReference<>();
        try {
            runOnMainSync(()->{
                display.set(getTargetContext().getSystemService(DisplayManager.class).createVirtualDisplay("Unbound pattern fixture",width,height,240,
                        reader.getSurface(),DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION));
                presentation.set(new DisplayTestPattern(menu,display.get().getDisplay(),stereo));presentation.get().show();
                check(DisplayTestScreens.read(menu).display==null,"An app-owned virtual display cannot be mistaken for physical glasses");
            });
            Image frame=null;long until=SystemClock.uptimeMillis()+8000;
            while(SystemClock.uptimeMillis()<until){
                frame=reader.acquireLatestImage();
                if(frame!=null&&green(frame,stereo?width/4:width/2,height/2))break;
                if(frame!=null){frame.close();frame=null;}SystemClock.sleep(25);
            }
            check(frame!=null,(stereo?"SBS":"2D")+" pattern renders to a real Android display surface");
            try {
                check(frame.getWidth()==width&&frame.getHeight()==height,"Pattern surface retains the requested dimensions");
                if(stereo)check(color(frame,width/4,height/2)==color(frame,width*3/4,height/2),"SBS halves contain matching centered eye patterns");
                int scale=4,w=width/scale,h=height/scale;int[] pixels=new int[w*h];
                for(int y=0;y<h;y++)for(int x=0;x<w;x++)pixels[y*w+x]=color(frame,x*scale,y*scale);
                Bitmap bitmap=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888);
                try(FileOutputStream output=new FileOutputStream(new File(getTargetContext().getCacheDir(),stereo?"display-test-sbs.png":"display-test-2d.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,output);}
                bitmap.recycle();
            }finally{frame.close();}
        }finally{
            runOnMainSync(()->{if(presentation.get()!=null)presentation.get().dismiss();if(display.get()!=null)display.get().release();});reader.close();
        }
    }
    private static int color(Image image,int x,int y){
        Image.Plane plane=image.getPlanes()[0];ByteBuffer pixels=plane.getBuffer();int at=y*plane.getRowStride()+x*plane.getPixelStride();
        return 0xff000000|((pixels.get(at)&255)<<16)|((pixels.get(at+1)&255)<<8)|(pixels.get(at+2)&255);
    }
    private static boolean green(Image image,int x,int y){int color=color(image,x,y);return ((color>>8)&255)>150&&((color>>16)&255)>80;}
}
