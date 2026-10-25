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
'android/os/Handler.java':'package android.os;public class Handler {public Handler(Looper l){}public void post(Runnable r){r.run();}public void postDelayed(Runnable r,long delay){}}',
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
with tempfile.TemporaryDirectory(prefix='dsh-native-actions-') as directory:
    root=pathlib.Path(directory)
    for name,text in STUBS.items():
        p=root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
    compiler=[shutil.which('javac')] if shutil.which('javac') else ['java','-m','jdk.compiler/com.sun.tools.javac.Main']
    sources=[SOURCE/(name+'.java') for name in ['JsonValue','FileOps','ShareTargets','NativeBridgePolicy','NativeBridgeServer','NativeBridge','NativeScreenCapture']]
    subprocess.run([*compiler,'-encoding','UTF-8','-d',str(root/'classes'),*map(str,[*root.rglob('*.java'),*sources,ROOT/'tests/NativeBridgeAndroidTest.java'])],check=True)
    subprocess.run(['java','-cp',str(root/'classes'),'dev.dsh.nativeapp.NativeBridgeAndroidTest'],check=True)
