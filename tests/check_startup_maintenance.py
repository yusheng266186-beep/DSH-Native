#!/usr/bin/env python3
"""Actual post-handoff scheduler, real Node probes and cancellation through the startup lock.

Android preferences/priority and task/paint signals are controlled boundaries, not a device.
"""
import pathlib
import shutil
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'src/dev/dsh/nativeapp'
if not SOURCE.is_dir():
    SOURCE = ROOT / 'bootstrap/src/dev/dsh/nativeapp'
text = (SOURCE / 'MainActivity.java').read_text()

def method(signature):
    start = text.index(signature)
    end = text.index('{', start) + 1
    depth = 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]

boot = method('private String boot()')
assert 'RuntimeChecks.preflight' not in boot and 'runCapture' not in boot
assert 'if (!preparedRuntimeValid) extractAssets(root);' in boot
assert 'preparedRuntimeValid ? 0 : RuntimePreparation.prepareTools' in boot
assert 'if (!preparedRuntimeValid) {\n            run(node' in boot
assert boot.count('.putString("corePreparedKey", preparedRuntimeKey(root))') == 2
assert 'cancelRuntimeInspection();' in method('private void beginStartup(')
assert 'scheduleRuntimeChecks();' in method('private void finishStartupMaintenance()')
assert 'interruptRuntimeInspection();' in method('protected void onPause()')
methods = '\n'.join(method(s) for s in ['private void cancelRuntimeInspection()',
    'private void interruptRuntimeInspection()', 'private void scheduleRuntimeChecks()'])
HARNESS = r'''
package dev.dsh.nativeapp;
import java.io.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import java.util.concurrent.locks.*;
class SessionStatus {static final int IDLE=0,RUNNING=1,UNKNOWN=-1;}
public class StartupMaintenanceProbe {
 static int checks;static final int MODE_PRIVATE=0;static final String PREFS="test";
 static void check(boolean v,String why){if(!v)throw new AssertionError(why);checks++;}
 final ReentrantLock runtimeConfigLock=new ReentrantLock();
 final StartupCoordinator startupCoordinator=new StartupCoordinator(runtimeConfigLock,Runnable::run);
 final OperationGate maintenanceGate=new OperationGate();
 final AtomicBoolean runtimeDiagnosticsQueued=new AtomicBoolean();
 final AtomicLong runtimeInspectionGeneration=new AtomicLong();
 volatile boolean runtimeDiagnosticsPending=true,splashHidden=true,inForeground=true,activityDisposing;
 volatile Thread runtimeInspectionWorker;File appRoot,workspace;int state=SessionStatus.IDLE;
 final android.content.SharedPreferences prefs=new android.content.SharedPreferences();
 final AtomicReference<Throwable> workerFailure=new AtomicReference<>();
 final List<Runnable> queued=new ArrayList<>();final AtomicInteger probes=new AtomicInteger();String key="environment-1";
 StartupMaintenanceProbe(File root){appRoot=root;workspace=root;}
 int deriveStatus(long now){return state;}
 String runtimeDiagnosticsKey(File root){return key;}
 android.content.SharedPreferences getSharedPreferences(String p,int m){return prefs;}
 String lastPreflightKey(){return prefs.getString("preflightKey","");}
 void rememberPreflightKey(String key){prefs.edit().putString("preflightKey",key).apply();}
 RuntimeChecks.Log runtimeLog(){return s->{if(s.equals("运行环境自检 …"))probes.incrementAndGet();};}
 void log(String message){}
 void startWorker(Runnable work,String name){queued.add(work);}
 Thread drain(){check(queued.size()==1,"one coalesced inspection worker");Runnable task=queued.remove(0);Thread t=new Thread(task);t.setUncaughtExceptionHandler((w,e)->workerFailure.set(e));t.start();return t;}
 void finish(Thread t)throws Exception{t.join(4000);check(!t.isAlive()&&!runtimeDiagnosticsQueued.get()&&workerFailure.get()==null,"inspection releases worker admission");}
 __METHODS__
 static void until(java.util.function.BooleanSupplier condition,String why)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(5);check(condition.getAsBoolean(),why);}
 public static void main(String[] args)throws Exception {
  File root=new File(args[0]);Files.createSymbolicLink(new File(root,"node").toPath(),Paths.get(args[1]));
  Files.createDirectories(new File(root,"tmp").toPath());
  Files.writeString(new File(root,"preflight.js").toPath(),"const fs=require('fs');fs.writeFileSync('inspection-started','1');if(fs.existsSync('slow'))setTimeout(()=>console.log('PREFLIGHT_END|0'),30000);else console.log('PREFLIGHT_END|'+(fs.existsSync('pass')?'0':'1'));\n");
  long now=10000000;
  check(!StartupDiagnostics.due("x","x","",0,now),"passed inspection cached independently of startup");
  check(StartupDiagnostics.due("x","","",0,now),"first inspection due");
  check(!StartupDiagnostics.due("x","","x",now-1,now),"failure does not retry on every open");
  check(StartupDiagnostics.due("x","","x",now-StartupDiagnostics.RETRY_MS,now),"failed inspection retries after backoff");
  check(StartupDiagnostics.due("y","x","x",now-1,now),"changed environment inspected immediately");
  check(StartupDiagnostics.due("x","","x",now+1,now),"wall clock reversal cannot prevent inspection");
  StartupMaintenanceProbe p=new StartupMaintenanceProbe(root);
  p.splashHidden=false;p.scheduleRuntimeChecks();check(p.queued.isEmpty(),"no probe before first-frame handoff");
  p.splashHidden=true;p.inForeground=false;p.scheduleRuntimeChecks();check(p.queued.isEmpty(),"no probe while backgrounded");
  p.inForeground=true;p.state=SessionStatus.UNKNOWN;p.scheduleRuntimeChecks();check(p.queued.isEmpty(),"unknown task evidence protects chat");
  p.state=SessionStatus.RUNNING;p.scheduleRuntimeChecks();check(p.queued.isEmpty(),"active chat protects latency");
  p.state=SessionStatus.IDLE;p.maintenanceGate.tryStart("update");p.scheduleRuntimeChecks();check(p.queued.isEmpty(),"maintenance has priority");p.maintenanceGate.finish("update");
  p.scheduleRuntimeChecks();p.scheduleRuntimeChecks();p.inForeground=false;p.finish(p.drain());check(p.probes.get()==0&&p.runtimeDiagnosticsPending,"queued probe rechecks foreground before running");
  p.inForeground=true;p.scheduleRuntimeChecks();p.cancelRuntimeInspection();p.runtimeDiagnosticsPending=true;p.finish(p.drain());check(p.probes.get()==0,"old queued generation cannot run after restart");
  p.scheduleRuntimeChecks();p.runtimeConfigLock.lock();try{p.finish(p.drain());}finally{p.runtimeConfigLock.unlock();}check(p.probes.get()==0&&p.runtimeDiagnosticsPending,"inspection never waits for an occupied environment lock");
  p.scheduleRuntimeChecks();p.finish(p.drain());check(p.probes.get()==1&&p.lastPreflightKey().isEmpty()&&!p.runtimeDiagnosticsPending,"actual failed Node inspection cannot mark success or block homepage");
  p.runtimeDiagnosticsPending=true;p.scheduleRuntimeChecks();p.finish(p.drain());check(p.probes.get()==1,"failed tool or DNS check is backed off across reopens");
  Files.writeString(new File(root,"pass").toPath(),"1");p.key="environment-2";p.runtimeDiagnosticsPending=true;p.scheduleRuntimeChecks();p.finish(p.drain());check(p.probes.get()==2&&p.lastPreflightKey().equals(p.key),"changed environment uses actual Node probe and caches only pass");
  p.runtimeDiagnosticsPending=true;p.scheduleRuntimeChecks();p.finish(p.drain());check(p.probes.get()==2,"passed inspection is reused without spawning Node");
  Files.writeString(new File(root,"slow").toPath(),"1");Files.delete(new File(root,"inspection-started").toPath());p.key="environment-3";p.runtimeDiagnosticsPending=true;p.scheduleRuntimeChecks();Thread inspection=p.drain();
  until(()->new File(root,"inspection-started").isFile(),"real 30-second probe reached child process");
  AtomicBoolean started=new AtomicBoolean();long start=System.nanoTime();
  StartupMaintenanceProbe current=p;
  check(p.startupCoordinator.start(()->{started.set(true);current.runtimeDiagnosticsPending=true;},e->{throw new AssertionError(e);},p::cancelRuntimeInspection),"restart admitted while slow inspection owns environment lock");
  until(started::get,"restart interrupts actual child and acquires environment lock without waiting 30 seconds");
  p.finish(inspection);check(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(4),"slow inspection is removed from restart critical path");
  check(p.runtimeDiagnosticsPending&&p.prefs.getString("preflightAttemptKey","").isEmpty(),"cancelled attempt clears backoff and cannot erase new startup pending state");
  until(()->!current.startupCoordinator.busy(),"restart worker completed");
  p.scheduleRuntimeChecks();inspection=p.drain();Thread.sleep(100);p.inForeground=false;p.interruptRuntimeInspection();p.finish(inspection);check(p.runtimeDiagnosticsPending,"background cancellation retains eligibility for a later idle inspection");
  p.activityDisposing=true;p.scheduleRuntimeChecks();check(p.queued.isEmpty(),"disposed Activity never queues inspection");p.startupCoordinator.close();
  System.out.println("Startup maintenance: "+checks+" actual scheduler/policy/real Node cancellation checks passed; no device.");
 }
}
'''
PREFS = '''package android.content;public class SharedPreferences {
 public final java.util.Map<String,Object> values=new java.util.concurrent.ConcurrentHashMap<>();
 public String getString(String k,String fallback){Object v=values.get(k);return v==null?fallback:(String)v;}
 public long getLong(String k,long fallback){Object v=values.get(k);return v==null?fallback:(Long)v;}
 public Editor edit(){return new Editor();}public class Editor {
 public Editor putString(String k,String v){values.put(k,v);return this;}public Editor putLong(String k,long v){values.put(k,v);return this;}
 public Editor remove(String k){values.remove(k);return this;}public void apply(){}}}'''
with tempfile.TemporaryDirectory(prefix='dsh-startup-maintenance-') as directory:
    root = pathlib.Path(directory)
    files = {'dev/dsh/nativeapp/ProjectModelSettings.java': 'package dev.dsh.nativeapp;class ProjectModelSettings {static String readFile(java.io.File f)throws java.io.IOException{return new String(java.nio.file.Files.readAllBytes(f.toPath()),"UTF-8");}}',
             'dev/dsh/nativeapp/StartupMaintenanceProbe.java': HARNESS.replace('__METHODS__', methods),
             'android/content/SharedPreferences.java': PREFS,
             'android/os/Process.java': 'package android.os;public class Process {public static final int THREAD_PRIORITY_BACKGROUND=10;public static void setThreadPriority(int n){}}',
             'android/os/SystemClock.java': 'package android.os;public class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}}'}
    for name, body in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body)
    compiler = [shutil.which('javac')] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
    dependencies = ['StartupCoordinator', 'StartupDiagnostics', 'OperationGate', 'RuntimeChecks', 'ProcessRunner', 'ToolEnvironment']
    subprocess.run([*compiler, '-encoding', 'UTF-8', '-d', str(root/'classes'), *map(str, root.rglob('*.java')),
                    *[str(SOURCE/(name+'.java')) for name in dependencies]], check=True)
    subprocess.run(['java', '-cp', str(root/'classes'), 'dev.dsh.nativeapp.StartupMaintenanceProbe', str(root), shutil.which('node')], check=True, timeout=35)
