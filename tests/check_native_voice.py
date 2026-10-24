#!/usr/bin/env python3
"""Run the production Android controller with deterministic OS boundary fakes.

No device, audio, network or ASR is used. This checks app routing and lifecycle,
not whether a particular ROM's microphone or speech engine actually works.
"""
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "src/dev/dsh/nativeapp"
if not SOURCE.is_dir():
    SOURCE = ROOT / "bootstrap/src/dev/dsh/nativeapp"

STUBS = {
    "android/Manifest.java": '''package android; public class Manifest { public static class permission { public static final String RECORD_AUDIO="microphone"; } }''',
    "android/content/ContentResolver.java": '''package android.content; public class ContentResolver {}''',
    "android/content/Context.java": '''package android.content; public class Context { public ContentResolver getContentResolver(){return new ContentResolver();} }''',
    "android/content/SharedPreferences.java": '''package android.content; import java.util.*; public class SharedPreferences {
        final Map<String,String> values=new HashMap<>(); public String getString(String key,String fallback){return values.getOrDefault(key,fallback);}
        public SharedPreferences edit(){return this;} public SharedPreferences putString(String key,String value){values.put(key,value);return this;} public void apply(){} }''',
    "android/content/ComponentName.java": '''package android.content; public class ComponentName {
        public final String name; public ComponentName(String name){this.name=name;}
        public ComponentName(String pkg,String cls){name=pkg+"/"+cls;} public String flattenToString(){return name;}
        public String getPackageName(){return name.split("/",2)[0];}
        public boolean equals(Object other){return other instanceof ComponentName&&name.equals(((ComponentName)other).name);}
        public int hashCode(){return name.hashCode();}
        public static ComponentName unflattenFromString(String value){return value!=null&&value.contains("/")?new ComponentName(value):null;}
    }''',
    "android/content/Intent.java": '''package android.content; import java.util.*; import android.content.pm.PackageManager;
        public class Intent { public final String action; public ComponentName component; public final Map<String,Object> extras=new HashMap<>();
        public Intent(String action){this.action=action;} public Intent setComponent(ComponentName value){component=value;return this;}
        public Intent putExtra(String key,Object value){extras.put(key,value);return this;}
        public ComponentName resolveActivity(PackageManager pm){return pm.window?new ComponentName(pm.windowPackage+"/VoiceActivity"):null;}
        @SuppressWarnings("unchecked") public ArrayList<String> getStringArrayListExtra(String key){return (ArrayList<String>)extras.get(key);}
    }''',
    "android/content/pm/ApplicationInfo.java": '''package android.content.pm; public class ApplicationInfo { public boolean enabled=true; }''',
    "android/content/pm/ServiceInfo.java": '''package android.content.pm; public class ServiceInfo { public boolean enabled=true,exported=true;
        public String packageName="synthetic",name="RecognitionService"; public ApplicationInfo applicationInfo=new ApplicationInfo(); }''',
    "android/content/pm/ResolveInfo.java": '''package android.content.pm; public class ResolveInfo { public ServiceInfo serviceInfo=new ServiceInfo();
        public CharSequence loadLabel(PackageManager pm){return "Synthetic speech";} }''',
    "android/content/pm/PackageManager.java": '''package android.content.pm; import java.util.*; import android.content.Intent;
        public class PackageManager { public static final int PERMISSION_GRANTED=0; public boolean window=true,configured=true;
        public String windowPackage="synthetic"; public final List<ResolveInfo> services=new ArrayList<>(Arrays.asList(new ResolveInfo()));
        public List<ResolveInfo> queryIntentServices(Intent intent,int flags){return configured?services:Collections.emptyList();}
    }''',
    "android/provider/Settings.java": '''package android.provider; import android.content.ContentResolver; public class Settings {
        public static class Secure { public static String selected="synthetic/RecognitionService";
        public static String getString(ContentResolver resolver,String name){return selected;} }
    }''',
    "android/app/Activity.java": '''package android.app; import android.content.*; import android.content.pm.PackageManager;
        public class Activity extends Context { public static final int RESULT_OK=-1, RESULT_CANCELED=0;
        public final PackageManager pm=new PackageManager(); public int permission=0,permissionRequests=0,windows=0; public boolean failWindow;
        public final SharedPreferences prefs=new SharedPreferences(); public SharedPreferences getSharedPreferences(String name,int mode){return prefs;}
        public Intent lastIntent; public int checkSelfPermission(String name){return permission;} public PackageManager getPackageManager(){return pm;}
        public void requestPermissions(String[] permissions,int request){permissionRequests++;}
        public void startActivity(Intent request){lastIntent=request;}
        public void startActivityForResult(Intent request,int code){if(failWindow)throw new IllegalStateException("synthetic");windows++;lastIntent=request;}
    }''',
    "android/os/Build.java": '''package android.os; public class Build { public static class VERSION {public static int SDK_INT=37;} }''',
    "android/os/Bundle.java": '''package android.os; import java.util.*; public class Bundle {
        private final Map<String,ArrayList<String>> values=new HashMap<>();
        public ArrayList<String> getStringArrayList(String key){return values.get(key);}
        public void putStringArrayList(String key,ArrayList<String> value){values.put(key,value);}
    }''',
    "android/os/SystemClock.java": '''package android.os; public class SystemClock { public static long now=1000; public static long uptimeMillis(){return now;} }''',
    "android/os/Handler.java": '''package android.os; import java.util.*; public class Handler {
        static class Work {Runnable task;long at;Work(Runnable task,long at){this.task=task;this.at=at;}}
        static final List<Work> queue=new ArrayList<>(); public void postDelayed(Runnable task,long delay){queue.add(new Work(task,SystemClock.now+delay));}
        public void removeCallbacks(Runnable task){queue.removeIf(work->work.task==task);}
        public static void reset(){queue.clear();SystemClock.now=1000;}
        public static void advance(long elapsed){long target=SystemClock.now+elapsed;while(true){
            Work next=queue.stream().filter(work->work.at<=target).min(Comparator.comparingLong(work->work.at)).orElse(null);
            if(next==null)break;queue.remove(next);SystemClock.now=next.at;next.task.run();}SystemClock.now=target;}
    }''',
    "android/util/Log.java": '''package android.util; public class Log { public static int w(String tag,String message){return 0;} }''',
    "android/speech/RecognitionListener.java": '''package android.speech; import android.os.Bundle; public interface RecognitionListener {
        void onReadyForSpeech(Bundle value);void onBeginningOfSpeech();void onRmsChanged(float value);void onBufferReceived(byte[] value);
        void onEndOfSpeech();void onError(int value);void onResults(Bundle value);void onPartialResults(Bundle value);void onEvent(int event,Bundle value);
    }''',
    "android/speech/RecognizerIntent.java": '''package android.speech; public class RecognizerIntent {
        public static final String ACTION_RECOGNIZE_SPEECH="android.speech.action.RECOGNIZE_SPEECH",EXTRA_LANGUAGE_MODEL="language-model",
        LANGUAGE_MODEL_FREE_FORM="free_form",EXTRA_LANGUAGE="language",EXTRA_MAX_RESULTS="max-results",EXTRA_PARTIAL_RESULTS="partial-results",EXTRA_RESULTS="results";
        public static final int RESULT_AUDIO_ERROR=5,RESULT_CLIENT_ERROR=2,RESULT_NETWORK_ERROR=4,RESULT_NO_MATCH=1,RESULT_SERVER_ERROR=3;
    }''',
    "android/speech/SpeechRecognizer.java": '''package android.speech; import android.content.*; import android.os.Bundle; import java.util.*;
        public class SpeechRecognizer { public static final String RESULTS_RECOGNITION="results_recognition";
        public static final List<SpeechRecognizer> created=new ArrayList<>(); public static boolean onDevice,failCreate,failStart,syncReady;
        public RecognitionListener listener;public boolean device,destroyed; public int stops;public ComponentName component;
        public static void reset(){created.clear();onDevice=false;failCreate=false;failStart=false;syncReady=false;}
        public static boolean isRecognitionAvailable(Context context){return true;}
        public static boolean isOnDeviceRecognitionAvailable(Context context){return onDevice;}
        public static SpeechRecognizer createOnDeviceSpeechRecognizer(Context context){SpeechRecognizer r=createSpeechRecognizer(context);r.device=true;return r;}
        public static SpeechRecognizer createSpeechRecognizer(Context context){if(failCreate)throw new IllegalStateException("synthetic create");
            SpeechRecognizer r=new SpeechRecognizer();created.add(r);return r;}
        public static SpeechRecognizer createSpeechRecognizer(Context context,ComponentName component){SpeechRecognizer r=createSpeechRecognizer(context);r.component=component;return r;}
        public void setRecognitionListener(RecognitionListener value){listener=value;}
        public void startListening(Intent request){if(failStart)throw new IllegalStateException("synthetic start");if(syncReady)listener.onReadyForSpeech(new Bundle());}
        public void stopListening(){stops++;} public void cancel(){}public void destroy(){destroyed=true;}
    }''',
    "org/json/JSONException.java": '''package org.json; public class JSONException extends Exception {}''',
    "org/json/JSONArray.java": '''package org.json; import java.util.*; public class JSONArray {final List<Object> values=new ArrayList<>();
        public JSONArray put(Object value){values.add(value);return this;} public String toString(){StringJoiner out=new StringJoiner(",","[","]");values.forEach(value->out.add(value.toString()));return out.toString();} }''',
    "org/json/JSONObject.java": r'''package org.json; import java.util.*; public class JSONObject {
        private final Map<String,Object> values=new LinkedHashMap<>();
        public JSONObject put(String key,Object value)throws JSONException{values.put(key,value);return this;}
        private String quote(Object value){return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\"";}
        public String toString(){StringJoiner out=new StringJoiner(",","{","}");values.forEach((key,value)->out.add(quote(key)+":"+(value instanceof String?quote(value):String.valueOf(value))));return out.toString();}
    }''',
}

with tempfile.TemporaryDirectory(prefix="dsh-native-voice-test-") as temporary:
    path = pathlib.Path(temporary)
    for name, source in STUBS.items():
        target = path / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source, encoding="utf-8")
    compiler = [shutil.which("javac")] if shutil.which("javac") else ["java", "-m", "jdk.compiler/com.sun.tools.javac.Main"]
    controller = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else SOURCE / "NativeVoiceController.java"
    files = [*path.rglob("*.java"), controller, SOURCE / "VoiceUi.java", SOURCE / "VoiceStartGate.java", SOURCE / "JsonValue.java", ROOT / "tests/NativeVoiceControllerTest.java"]
    subprocess.run([*compiler, "-encoding", "UTF-8", "-d", str(path / "classes"), *map(str, files)], check=True)
    subprocess.run(["java", "-cp", str(path / "classes"), "dev.dsh.nativeapp.NativeVoiceControllerTest"], check=True)
