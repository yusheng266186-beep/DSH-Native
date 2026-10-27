#!/usr/bin/env python3
"""Run actual upgrade/reuse methods with real authenticated localhost task RPC.

Android preferences, process ownership and UI are isolated boundaries. No device.
"""
import pathlib
import shutil
import subprocess
import tempfile
ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'src/dev/dsh/nativeapp'
if not SOURCE.is_dir(): SOURCE = ROOT / 'bootstrap/src/dev/dsh/nativeapp'
text = (SOURCE / 'MainActivity.java').read_text()
def method(signature):
    start = text.index(signature); opening = text.index('{', start); depth = 1; end = opening + 1
    while depth:
        if text[end] == '{': depth += 1
        elif text[end] == '}': depth -= 1
        end += 1
    return text[start:end]
methods = '\n'.join(method(s) for s in ['private boolean reuseOrStopPreviousAppCore()', 'private boolean reusePreparedCore('])
HARNESS = r'''
package dev.dsh.nativeapp;
import java.io.*;import java.util.*;import java.nio.file.*;import java.net.*;import com.sun.net.httpserver.*;
class HarnessService {
 static boolean alive=true,stops=true;static int calls;static String url;
 static boolean isManagedProcessAlive(){return alive;}static String managedUrl(){return url;}
 static boolean stopManagedProcessAndWait(long ms){calls++;return stops;}
}
// This RPC read is not used by the methods under test; their task RPC is real.
class ModelSettingsSnapshot {static String fromDescriptionObject(Object v){throw new AssertionError("unexpected settings read");}}
public class StartupRuntimeProbe {
 static final int MODE_PRIVATE=0;static final String PREFS="fixture";
 static int checks,taskReads;static String tasks="false";
 final android.content.SharedPreferences prefs=new android.content.SharedPreferences();
 boolean preparedRuntimeValid,preparedCoreReused,payloadCheckAfterStartup,complete=true,assetsPresent=true,pending,hold;
 File workspace,nodeRef,toolsDirRef;int live,chosenPort;long launchStartedAt;String loaded;
 String appVersion(){return "1.0.0";}String savedDshToken(){return "synthetic-token";}
 android.content.SharedPreferences getSharedPreferences(String p,int mode){return prefs;}
 void log(String s){}void loadDshUi(String url){loaded=url;}void registerNetworkWatcher(){}
 void recordPatch(String name,boolean ok,String details){}void setSplashStatus(String text){}
 String preparedRuntimeKey(File root){return "reviewed-key";}String payloadRevisionKey(){return "v13";}
 static class Components {String identity="asset-contents"; boolean ready(File root){return true;}}
 Components components(){return new Components();}
 String lastPreflightKey(){return "v13/asset-contents/"+workspace.getAbsolutePath();}
 boolean runtimeAssetsPresent(File root){return assetsPresent;}boolean payloadLocallyComplete(File a,File b){return complete;}
 boolean payloadUpdatePending(){return pending;}boolean payloadRollbackHold(){return hold;}
 String validatePayloadManifest(File file){return null;}int liveDshPort(){return live;}
 String readText(File file)throws IOException{return Files.readString(file.toPath());}
 __METHODS__
 static void check(boolean v,String why){if(!v)throw new AssertionError(why);checks++;}
 static StartupRuntimeProbe fixture(){StartupRuntimeProbe p=new StartupRuntimeProbe();p.prefs.values.put("coreAppVersion","0.40.2");p.prefs.values.put("dshPort","3080");p.prefs.values.put("dshToken","synthetic-token");p.prefs.values.put("corePreparedKey","reviewed-key");p.prefs.values.put("private-canary","keep");HarnessService.alive=true;HarnessService.stops=true;HarnessService.calls=0;return p;}
 public static void main(String[] args)throws Exception {
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",e->{e.getResponseHeaders().add("Location","/");e.getResponseHeaders().add("Set-Cookie","dsh_session=fixture; HttpOnly");e.sendResponseHeaders(303,-1);e.close();});
  server.createContext("/api/session/list",e->{taskReads++;if(!"dsh_session=fixture".equals(e.getRequestHeaders().getFirst("Cookie")))throw new AssertionError("authentication required");Map<?,?> req=(Map<?,?>)JsonValue.parse(new String(e.getRequestBody().readAllBytes(),"UTF-8"));String item=tasks.equals("unknown")?"{}":"{\"running\":"+tasks+"}";byte[] body=("{\"type\":\"server-response\",\"rpcId\":\""+req.get("rpcId")+"\",\"result\":{\"ok\":true,\"value\":{\"items\":["+item+"]}}}").getBytes("UTF-8");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});server.start();
  HarnessService.url="http://127.0.0.1:"+server.getAddress().getPort()+"/?token=synthetic-token";
  try{
   StartupRuntimeProbe p=fixture();check(!p.reuseOrStopPreviousAppCore()&&HarnessService.calls==1&&p.loaded==null,"idle old core exits before preparation without requiring manual retry");
   check(!p.prefs.values.containsKey("dshToken")&&"keep".equals(p.prefs.values.get("private-canary")),"idle upgrade invalidates old address only and preserves private data");
   p=fixture();tasks="true";check(p.reuseOrStopPreviousAppCore()&&HarnessService.calls==0&&p.loaded.equals(HarnessService.url),"active core retains service and UI");
   p=fixture();tasks="unknown";check(p.reuseOrStopPreviousAppCore()&&HarnessService.calls==0&&p.loaded!=null,"unknown task evidence never means safe to stop");
   p=fixture();tasks="false";HarnessService.stops=false;try{p.reuseOrStopPreviousAppCore();throw new AssertionError("stop timeout accepted");}catch(IOException expected){checks++;}
   check(p.prefs.values.containsKey("dshToken"),"stop timeout preserves recovery address");
   p=fixture();p.prefs.values.put("coreAppVersion","1.0.0");int before=taskReads;check(!p.reuseOrStopPreviousAppCore()&&taskReads==before&&HarnessService.calls==0,"current core avoids upgrade RPC and shutdown");
   File root=new File(args[0]);Files.writeString(new File(root,".assets-version").toPath(),"1.0.0");
   p=fixture();p.workspace=root;p.prefs.values.put("coreAppVersion","1.0.0");
   check(!p.reusePreparedCore(root,root,root,root)&&p.preparedRuntimeValid,"a dead process retains verified disk preparation for a short cold start");
   p=fixture();p.workspace=root;p.prefs.values.put("coreAppVersion","1.0.0");p.pending=true;
   check(!p.reusePreparedCore(root,root,root,root)&&!p.preparedRuntimeValid,"pending payload update requires full preparation");
   p=fixture();p.workspace=root;p.prefs.values.put("coreAppVersion","1.0.0");p.assetsPresent=false;
   check(!p.reusePreparedCore(root,root,root,root)&&!p.preparedRuntimeValid,"missing bootstrap assets require repair");
   System.out.println("Startup runtime: "+checks+" production upgrade/reuse and real authenticated task RPC checks passed; no device.");
  }finally{server.stop(0);}
 }
}
'''
PREFS = '''package android.content;public class SharedPreferences {public final java.util.Map<String,String> values=new java.util.HashMap<>();public String getString(String key,String fallback){return values.getOrDefault(key,fallback);}public Editor edit(){return new Editor();}public class Editor {public Editor remove(String key){values.remove(key);return this;}public void apply(){}}}'''
CLOCK = 'package android.os;public class SystemClock {public static long elapsedRealtime(){return 1000L;}}'
with tempfile.TemporaryDirectory(prefix='dsh-startup-runtime-') as directory:
    root=pathlib.Path(directory)
    for name,body in {'android/content/SharedPreferences.java':PREFS,'android/os/SystemClock.java':CLOCK,'dev/dsh/nativeapp/StartupRuntimeProbe.java':HARNESS.replace('__METHODS__',methods)}.items():
        path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body)
    compiler=[shutil.which('javac')] if shutil.which('javac') else ['java','-m','jdk.compiler/com.sun.tools.javac.Main']
    files=['CoreRpcClient','CoreTaskState','JsonValue','SessionStatus','ConnectionRecovery','UiText','StartupReuse']
    subprocess.run([*compiler,'-encoding','UTF-8','-d',str(root/'classes'),*map(str,root.rglob('*.java')),*[str(SOURCE/(n+'.java')) for n in files]],check=True)
    subprocess.run(['java','-cp',str(root/'classes'),'dev.dsh.nativeapp.StartupRuntimeProbe',str(root)],check=True)
