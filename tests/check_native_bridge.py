#!/usr/bin/env python3
"""Execute production native actions/coordinator with deterministic Android API fakes.
No device, clipboard, notifications, external app or projection is accessed.
"""
import pathlib, shutil, subprocess, tempfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
SOURCE=ROOT/'src/dev/dsh/nativeapp'
if not SOURCE.is_dir(): SOURCE=ROOT/'bootstrap/src/dev/dsh/nativeapp'
STUBS={
'android/os/Build.java':'package android.os;public class Build {public static class VERSION {public static int SDK_INT=37;}}',
'android/os/Looper.java':'package android.os;public class Looper {public static Looper getMainLooper(){return new Looper();}}',
'android/os/Handler.java':'package android.os;public class Handler {public Handler(Looper l){}public void post(Runnable r){r.run();}public void postDelayed(Runnable r,long delay){}public void removeCallbacks(Runnable r){}}',
'android/os/SystemClock.java':'package android.os;public class SystemClock {public static long time=10000;public static long uptimeMillis(){return time;}}',
'android/net/Uri.java':'package android.net;public class Uri {public String value;public static Uri parse(String value){Uri uri=new Uri();uri.value=value;return uri;}}',
'android/content/Intent.java':'''package android.content;import java.util.*;import android.net.Uri;public class Intent {
public static final String ACTION_SEND="send",ACTION_VIEW="view",EXTRA_TEXT="text",EXTRA_STREAM="stream",CATEGORY_BROWSABLE="browser";
public static final int FLAG_GRANT_READ_URI_PERMISSION=1;public String action,type;public Uri uri;public ClipData clip;public int flags;public final Map<String,Object> extras=new HashMap<>();
public Intent(String action){this.action=action;}public Intent(Context ctx,Class<?> type){action=type.getSimpleName();}
public Intent(String action,Uri uri){this.action=action;this.uri=uri;}public Intent setType(String type){this.type=type;return this;}
public Intent setDataAndType(Uri uri,String type){this.uri=uri;this.type=type;return this;}public Intent setClipData(ClipData clip){this.clip=clip;return this;}
public Intent putExtra(String key,Object value){extras.put(key,value);return this;}public Intent addFlags(int flags){this.flags|=flags;return this;}
public Intent addCategory(String category){return this;}public static Intent createChooser(Intent intent,String title){return intent;}
}''',
'android/content/ClipData.java':'''package android.content;import android.net.Uri;public class ClipData {public CharSequence text;public Uri uri;
public static ClipData newPlainText(String label,CharSequence value){ClipData clip=new ClipData();clip.text=value;return clip;}
public static ClipData newRawUri(String label,Uri value){ClipData clip=new ClipData();clip.uri=value;return clip;}
public int getItemCount(){return 1;}public Item getItemAt(int at){return new Item(text);}public static class Item {CharSequence text;Item(CharSequence text){this.text=text;}public CharSequence getText(){return text;}}}''',
'android/content/ClipboardManager.java':'''package android.content;public class ClipboardManager {public ClipData value;public int reads,writes;
public void setPrimaryClip(ClipData value){this.value=value;writes++;}public ClipData getPrimaryClip(){reads++;return value;}}''',
'android/content/pm/PackageManager.java':'''package android.content.pm;import android.content.Intent;public class PackageManager {public Intent getLaunchIntentForPackage(String value){return value.equals("synthetic.available")?new Intent("launch"):null;}}''',
'android/content/Context.java':'''package android.content;import android.app.*;import android.media.projection.*;import android.content.pm.PackageManager;public class Context {
public static final String CLIPBOARD_SERVICE="clipboard",NOTIFICATION_SERVICE="notification",MEDIA_PROJECTION_SERVICE="projection";
public ClipboardManager clipboard=new ClipboardManager();public NotificationManager notifications=new NotificationManager();public MediaProjectionManager projection=new MediaProjectionManager();
public Intent lastService;public Object getSystemService(String name){return CLIPBOARD_SERVICE.equals(name)?clipboard:NOTIFICATION_SERVICE.equals(name)?notifications:projection;}
public PackageManager getPackageManager(){return new PackageManager();}public void startForegroundService(Intent intent){lastService=intent;}public void startService(Intent intent){lastService=intent;}
}''',
'android/app/Activity.java':'''package android.app;import android.content.*;public class Activity extends Context {public static final int RESULT_OK=-1,RESULT_CANCELED=0;
public Intent lastIntent;public int requests;public void startActivity(Intent intent){lastIntent=intent;}public void startActivityForResult(Intent intent,int request){lastIntent=intent;requests++;}}''',
'android/app/PendingIntent.java':'package android.app;import android.content.*;public class PendingIntent {public static final int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2;public static PendingIntent getActivity(Context c,int id,Intent i,int flags){return new PendingIntent();}}',
'android/app/Notification.java':'''package android.app;import android.content.Context;public class Notification {public static class BigTextStyle {public BigTextStyle bigText(String text){return this;}}
public static class Builder {public Builder(Context context){}public Builder(Context c,String channel){}public Builder setContentTitle(String value){return this;}public Builder setContentText(String value){return this;}
public Builder setStyle(BigTextStyle style){return this;}public Builder setSmallIcon(int icon){return this;}public Builder setAutoCancel(boolean auto){return this;}public Builder setContentIntent(PendingIntent intent){return this;}public Notification build(){return new Notification();}}}''',
'android/app/NotificationChannel.java':'package android.app;public class NotificationChannel {public int importance=3;public int getImportance(){return importance;}}',
'android/app/NotificationManager.java':'''package android.app;public class NotificationManager {public static final int IMPORTANCE_DEFAULT=3;public boolean enabled=true;public NotificationChannel channel=new NotificationChannel();public int count,lastId;
public boolean areNotificationsEnabled(){return enabled;}public NotificationChannel getNotificationChannel(String id){return channel;}public void notify(int id,Notification n){lastId=id;count++;}}''',
'android/media/projection/MediaProjectionManager.java':'package android.media.projection;import android.content.Intent;public class MediaProjectionManager {public Intent createScreenCaptureIntent(){return new Intent("system-capture-consent");}}',
'android/R.java':'package android;public class R {public static class drawable {public static final int stat_notify_sync=1;}}',
'dev/dsh/nativeapp/HarnessService.java':'''package dev.dsh.nativeapp;import java.io.File;public class HarnessService extends android.content.Context {public File files,cache;HarnessService(File root){files=new File(root,"files");cache=new File(root,"cache");files.mkdirs();cache.mkdirs();}public File getFilesDir(){return files;}public File getCacheDir(){return cache;}Object nativeKeepaliveStatus(){return NativeBridgePolicy.object("foregroundService",true,"wakeLockHeld",false);}}''',
'dev/dsh/nativeapp/MainActivity.java':'package dev.dsh.nativeapp;import java.io.File;public class MainActivity extends android.app.Activity {public boolean ready=true;public File workspace;boolean nativeBridgeForeground(){return ready;}File nativeBridgeWorkspace(){return workspace;}}',
'dev/dsh/nativeapp/DshUi.java':'package dev.dsh.nativeapp;public class DshUi {static void ensureChannel(android.content.Context c,String id,String name,String description,int importance){}}',
'dev/dsh/nativeapp/UpdateProvider.java':'package dev.dsh.nativeapp;class UpdateProvider {static final String AUTHORITY="synthetic.provider";}',
'dev/dsh/nativeapp/NativeCaptureService.java':'package dev.dsh.nativeapp;class NativeCaptureService {static int cancels;static void cancel(String id){cancels++;}}',
}
# These fakes execute the real projection service; they do not capture a device or encode a real PNG.
del STUBS['dev/dsh/nativeapp/NativeCaptureService.java']
STUBS.update({
'android/os/IBinder.java':'package android.os;public interface IBinder {}',
'android/os/HandlerThread.java':'''package android.os;public class HandlerThread {public static int active;public HandlerThread(String name){}public void start(){active++;}public Looper getLooper(){return new Looper();}public boolean quitSafely(){active--;return true;}}''',
'android/os/Handler.java':'''package android.os;import java.util.*;public class Handler {
public static final List<Handler> instances=new ArrayList<>();public final List<Runnable> delayed=new ArrayList<>();
public static Runnable beforePost;public static int postsBeforeHook;public static boolean rejectPosts,queuePosts,asyncPosts;
public final List<Runnable> queued=new java.util.concurrent.CopyOnWriteArrayList<>();
public Handler(Looper l){instances.add(this);}public boolean post(Runnable r){if(rejectPosts)return false;if(beforePost!=null&&--postsBeforeHook<=0){Runnable hook=beforePost;beforePost=null;hook.run();}if(queuePosts)queued.add(r);else if(asyncPosts)new Thread(r).start();else r.run();return true;}
public boolean postDelayed(Runnable r,long delay){delayed.add(r);return true;}public void removeCallbacks(Runnable r){delayed.remove(r);}
public void removeCallbacksAndMessages(Object token){delayed.clear();}public void fireDelayed(){List<Runnable> copy=new ArrayList<>(delayed);delayed.clear();for(Runnable r:copy)r.run();}
public static int pending(){int n=0;for(Handler h:instances)n+=h.delayed.size();return n;}}
''',
'android/util/DisplayMetrics.java':'package android.util;public class DisplayMetrics {public int widthPixels=2,heightPixels=3;}',
'android/util/Log.java':'package android.util;public class Log {public static int w(String tag,String message){return 0;}}',
'android/content/res/Configuration.java':'package android.content.res;public class Configuration {public int densityDpi=160;}',
'android/content/res/Resources.java':'package android.content.res;public class Resources {public Configuration getConfiguration(){return new Configuration();}}',
'android/view/Surface.java':'package android.view;public class Surface {}',
'android/view/WindowManager.java':'package android.view;import android.util.DisplayMetrics;public class WindowManager {public WindowManager getDefaultDisplay(){return this;}public void getRealMetrics(DisplayMetrics m){m.widthPixels=2;m.heightPixels=3;}}',
'android/graphics/PixelFormat.java':'package android.graphics;public class PixelFormat {public static final int RGBA_8888=1;}',
'android/graphics/Bitmap.java':'''package android.graphics;import java.nio.*;import java.io.*;public class Bitmap {
public static int live;public static boolean failMemory;public int w,h;byte[] bytes;boolean recycled;
public enum Config {ARGB_8888}public enum CompressFormat {PNG}
public static Bitmap createBitmap(int w,int h,Config c){if(failMemory)throw new OutOfMemoryError();Bitmap b=new Bitmap();b.w=w;b.h=h;live++;return b;}
public static Bitmap createBitmap(Bitmap source,int x,int y,int w,int h){if(source.w==w&&source.h==h)return source;Bitmap b=createBitmap(w,h,Config.ARGB_8888);b.bytes=source.bytes;return b;}
public void copyPixelsFromBuffer(Buffer raw){ByteBuffer b=(ByteBuffer)raw;bytes=new byte[w*h*4];b.get(bytes);}
public boolean compress(CompressFormat f,int quality,OutputStream out){try{out.write(bytes);return true;}catch(IOException error){return false;}}
public void recycle(){if(!recycled){live--;recycled=true;}}
}''',
'android/media/Image.java':'''package android.media;import java.nio.ByteBuffer;public class Image implements AutoCloseable {
public boolean closed;Plane plane;public Image(int row,int pixel,ByteBuffer buffer){plane=new Plane(row,pixel,buffer);}public Plane[] getPlanes(){return new Plane[]{plane};}public void close(){closed=true;}
public static class Plane {int row,pixel;ByteBuffer buffer;Plane(int row,int pixel,ByteBuffer b){this.row=row;this.pixel=pixel;buffer=b;}public int getPixelStride(){return pixel;}public int getRowStride(){return row;}public ByteBuffer getBuffer(){return buffer;}}}''',
'android/media/ImageReader.java':'''package android.media;import android.view.Surface;import android.os.Handler;public class ImageReader {
public static ImageReader latest;public static boolean failMemory;public boolean closed;public int width,height;Image next;OnImageAvailableListener listener;
public interface OnImageAvailableListener {void onImageAvailable(ImageReader reader);}
public static ImageReader newInstance(int w,int h,int format,int count){if(failMemory)throw new OutOfMemoryError();ImageReader r=new ImageReader();r.width=w;r.height=h;latest=r;return r;}
public void setOnImageAvailableListener(OnImageAvailableListener l,Handler h){listener=l;}public Surface getSurface(){return new Surface();}
public Image acquireLatestImage(){Image value=next;next=null;return value;}public void emit(Image image){next=image;listener.onImageAvailable(this);}public void close(){closed=true;if(next!=null)next.close();}}
''',
'android/hardware/display/DisplayManager.java':'package android.hardware.display;public class DisplayManager {public static final int VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR=1;}',
'android/hardware/display/VirtualDisplay.java':'package android.hardware.display;import android.view.Surface;public class VirtualDisplay {public boolean released,failResize,failSurface;public int resizes,surfaces;public void release(){released=true;}public void resize(int w,int h,int density){if(failResize)throw new IllegalStateException();resizes++;}public void setSurface(Surface surface){if(failSurface)throw new IllegalStateException();surfaces++;}}',
'android/media/projection/MediaProjection.java':'''package android.media.projection;import android.hardware.display.*;import android.os.Handler;import android.view.Surface;public class MediaProjection {
public Callback callback;public VirtualDisplay display;public int displays;public boolean stopped;
public static class Callback {public void onStop(){}public void onCapturedContentResize(int w,int h){}}
public void registerCallback(Callback c,Handler h){callback=c;}public VirtualDisplay createVirtualDisplay(String name,int w,int h,int density,int flags,Surface s,Object c,Handler handler){if(callback==null||++displays>1)throw new IllegalStateException();return display=new VirtualDisplay();}
public void stop(){if(!stopped){stopped=true;callback.onStop();}}}''',
'android/media/projection/MediaProjectionManager.java':'''package android.media.projection;import android.content.Intent;public class MediaProjectionManager {
public MediaProjection projection;public boolean fail;public Intent createScreenCaptureIntent(){return new Intent("system-capture-consent");}
public MediaProjection getMediaProjection(int result,Intent data){if(fail||android.app.Service.foregroundCount==0)throw new IllegalStateException();return projection=new MediaProjection();}}''',
'android/app/Service.java':'''package android.app;import android.content.*;import android.os.IBinder;public class Service extends Context {
public static int foregroundCount;public static final int START_NOT_STICKY=2;public boolean stopRequested,foreground;public int starts;
public IBinder onBind(Intent i){return null;}public void onCreate(){}public int onStartCommand(Intent i,int flags,int id){return START_NOT_STICKY;}public void onDestroy(){}
public void startForeground(int id,Notification n){foreground=true;foregroundCount++;starts++;}public void startForeground(int id,Notification n,int type){startForeground(id,n);}
public void stopForeground(boolean remove){foreground=false;}public void stopSelf(){stopRequested=true;}}
''',
})
STUBS['android/content/Context.java']=STUBS['android/content/Context.java'].replace('MEDIA_PROJECTION_SERVICE="projection";', 'MEDIA_PROJECTION_SERVICE="projection",WINDOW_SERVICE="window";').replace('public Object getSystemService(String name){return', 'public android.content.res.Resources getResources(){return new android.content.res.Resources();}public Object getSystemService(String name){if(WINDOW_SERVICE.equals(name))return new android.view.WindowManager();return')
STUBS['android/content/Context.java']=STUBS['android/content/Context.java'].replace('MediaProjectionManager projection=', 'MediaProjectionManager projectionManager=').replace(':projection;', ':projectionManager;')
STUBS['android/content/Intent.java']=STUBS['android/content/Intent.java'].replace('public Intent putExtra', 'public String getStringExtra(String key){return (String)extras.get(key);}public Object getParcelableExtra(String key){return extras.get(key);}public Intent putExtra')
STUBS['android/app/Activity.java']=STUBS['android/app/Activity.java'].replace('public int requests;', 'public int requests,lastRequestCode;').replace('lastIntent=intent;requests++;', 'lastIntent=intent;lastRequestCode=request;requests++;')
STUBS['android/app/Notification.java']=STUBS['android/app/Notification.java'].replace('public Builder setAutoCancel', 'public Builder setOngoing(boolean b){return this;}public Builder setAutoCancel')
STUBS['android/app/NotificationManager.java']=STUBS['android/app/NotificationManager.java'].replace('IMPORTANCE_DEFAULT=3;', 'IMPORTANCE_DEFAULT=3,IMPORTANCE_LOW=1;')
STUBS['android/R.java']=STUBS['android/R.java'].replace('stat_notify_sync=1;', 'stat_notify_sync=1,ic_menu_camera=2;')
STUBS['android/os/Handler.java']=STUBS['android/os/Handler.java'].replace('delayed.remove(r);','delayed.remove(r);queued.remove(r);')
STUBS['android/content/ActivityNotFoundException.java']='package android.content;public class ActivityNotFoundException extends RuntimeException {public ActivityNotFoundException(String message){super(message);}}'
STUBS['android/app/Activity.java']=STUBS['android/app/Activity.java'].replace('public void startActivity(Intent intent){lastIntent=intent;}', 'public RuntimeException launchFailure;public void startActivity(Intent intent){if(launchFailure!=null)throw launchFailure;lastIntent=intent;}')
STUBS['android/app/Activity.java']=STUBS['android/app/Activity.java'].replace('public void startActivityForResult(Intent intent,int request){', 'public Runnable captureHook;public void startActivityForResult(Intent intent,int request){if(captureHook!=null)captureHook.run();')
STUBS['android/content/ClipboardManager.java']=STUBS['android/content/ClipboardManager.java'].replace('public ClipData value;', 'public ClipData value;public RuntimeException failure;').replace('this.value=value;writes++;','if(failure!=null)throw failure;this.value=value;writes++;').replace('reads++;return value;', 'if(failure!=null)throw failure;reads++;return value;')
STUBS['android/app/NotificationManager.java']=STUBS['android/app/NotificationManager.java'].replace('public int count,lastId;', 'public int count,lastId;public RuntimeException failure;').replace('lastId=id;count++;', 'if(failure!=null)throw failure;lastId=id;count++;')
with tempfile.TemporaryDirectory(prefix='dsh-native-actions-') as directory:
    root=pathlib.Path(directory)
    for name,text in STUBS.items():
        p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    compiler=[shutil.which('javac')] if shutil.which('javac') else ['java','-m','jdk.compiler/com.sun.tools.javac.Main']
    sources=[SOURCE/(name+'.java') for name in ['NativeCaptureService','JsonValue','FileOps','ShareTargets','NativeBridgePolicy','NativeBridgeServer','NativeBridge','NativeScreenCapture']]
    subprocess.run([*compiler,'-encoding','UTF-8','-d',str(root/'classes'),*map(str,[*root.rglob('*.java'),*sources,ROOT/'tests/NativeBridgeAndroidTest.java',ROOT/'tests/NativeCaptureServiceTest.java',ROOT/'tests/NativeActionReliabilityTest.java'])],check=True)
    subprocess.run(['java','-cp',str(root/'classes'),'dev.dsh.nativeapp.NativeBridgeAndroidTest'],check=True)
    subprocess.run(['java','-cp',str(root/'classes'),'dev.dsh.nativeapp.NativeCaptureServiceTest'],check=True)
    subprocess.run(['java','-cp',str(root/'classes'),'dev.dsh.nativeapp.NativeActionReliabilityTest'],check=True)
