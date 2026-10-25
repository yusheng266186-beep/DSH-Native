package dev.dsh.nativeapp;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import android.content.*;
import android.app.Activity;
public class NativeBridgeAndroidTest {
 private static int checks;
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
 static Map<?,?> call(NativeBridge bridge,String method,Object...fields)throws Exception{Map<String,Object> command=NativeBridgePolicy.object("method",method);for(int i=0;i<fields.length;i+=2)command.put((String)fields[i],fields[i+1]);return (Map<?,?>)bridge.execute(NativeBridgePolicy.request(NativeBridgePolicy.json(command)));}
 static void denied(NativeBridge bridge,String method,String code,Object...fields)throws Exception{try{call(bridge,method,fields);throw new AssertionError(method);}catch(NativeBridgeServer.Failure failure){check(code.equals(failure.code),method+" wrong error "+failure.code);}}
 public static void main(String[]args)throws Exception{
  Path directory=Files.createTempDirectory("dsh-native-actions-").toRealPath();NativeBridge bridge=null;
  try{
   HarnessService service=new HarnessService(directory.toFile());MainActivity ui=new MainActivity();ui.workspace=Files.createDirectory(directory.resolve("workspace")).toFile();NativeBridge.attach(ui);bridge=new NativeBridge(service);
   check(Boolean.TRUE.equals(call(bridge,"capabilities").get("foregroundReady")),"foreground capability");
   check(Boolean.FALSE.equals(call(bridge,"clipboard.get").get("hasText")),"empty clipboard");
   call(bridge,"clipboard.set","text","private synthetic text");check(service.clipboard.writes==1,"native clipboard set");
   check(call(bridge,"clipboard.get").get("text").equals("private synthetic text"),"native clipboard get");
   service.clipboard.value=ClipData.newRawUri("synthetic",android.net.Uri.parse("content://private"));
   check(Boolean.FALSE.equals(call(bridge,"clipboard.get").get("hasText")),"URI clipboard never coerces unrelated private data");
   ui.ready=false;int before=service.clipboard.reads;
   denied(bridge,"clipboard.get","FOREGROUND_REQUIRED");denied(bridge,"clipboard.set","FOREGROUND_REQUIRED","text","blocked");
   check(service.clipboard.reads==before,"background clipboard never accessed");
   check(Boolean.TRUE.equals(call(bridge,"keepalive.status").get("foregroundService")),"background keepalive reports service");
   call(bridge,"notification.post","title","synthetic","text","body");check(service.notifications.count==1&&service.notifications.lastId==0x4461,"background notification uses distinct id");
   denied(bridge,"notification.post","RATE_LIMITED","title","synthetic","text","body");
   service.notifications.enabled=false;denied(bridge,"notification.post","NOTIFICATIONS_DISABLED","title","synthetic","text","body");
   service.notifications.enabled=true;service.notifications.channel.importance=0;denied(bridge,"notification.post","NOTIFICATIONS_DISABLED","title","synthetic","text","body");service.notifications.channel.importance=3;
   denied(bridge,"share.text","FOREGROUND_REQUIRED","text","blocked");ui.ready=true;
   check(Boolean.TRUE.equals(call(bridge,"share.text","text","synthetic shared text").get("chooserOpened"))&&ui.lastIntent.extras.get(Intent.EXTRA_TEXT).equals("synthetic shared text"),"text sharing uses actual chooser intent");
   check(Boolean.FALSE.equals(call(bridge,"share.text","text","synthetic").get("delivered")),"chooser is not delivery");
   call(bridge,"url.open","url","https://example.com/");check(ui.lastIntent.uri.value.equals("https://example.com/"),"HTTP link intent");
   call(bridge,"app.open","package","synthetic.available");check(ui.lastIntent.action.equals("launch"),"package launch intent");denied(bridge,"app.open","APP_UNAVAILABLE","package","synthetic.absent");
   Path report=ui.workspace.toPath().resolve("report.txt");Files.write(report,"FILE-CONTENT".getBytes("UTF-8"));call(bridge,"share.file","path",report.toString());
   check(ui.lastIntent.clip!=null&&ui.lastIntent.clip.uri.value.startsWith("content://synthetic.provider/f/"),"share uses content URI and read grant");
   check((ui.lastIntent.flags&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0,"share grants read only");
   check(ui.lastIntent.extras.get(Intent.EXTRA_STREAM)==ui.lastIntent.clip.uri,"share stream matches grant");
   call(bridge,"file.open","path",report.toString());check(ui.lastIntent.action.equals(Intent.ACTION_VIEW)&&ui.lastIntent.uri.value.startsWith("content://"),"file open uses viewer and staged content URI");
   Path secret=ui.workspace.toPath().resolve(".env");Files.write(secret,new byte[]{1});denied(bridge,"share.file","FILE_NOT_ALLOWED","path",secret.toString());
   check(new String(Files.readAllBytes(report),"UTF-8").equals("FILE-CONTENT"),"sharing leaves source unchanged");
   String longName=new String(new char[225]).replace('\0','x')+".txt";Path longFile=ui.workspace.toPath().resolve(longName);Files.write(longFile,new byte[]{1});call(bridge,"share.file","path",longFile.toString());
   java.io.File staged=ShareTargets.decode(ui.lastIntent.clip.uri.value.split("/f/",2)[1]);
   check(staged.getName().equals(longName)&&staged.isFile(),"long original filename preserved in unique staging directory");
   check(ui.lastIntent.type.equals("text/plain"),"long file MIME preserved");
   service.clipboard.value=ClipData.newPlainText("oversized",new String(new char[16001]).replace('\0','x'));denied(bridge,"clipboard.get","TEXT_TOO_LONG");
   NativeScreenCapture.Request request=NativeScreenCapture.request(ui,service.getFilesDir());check(ui.lastIntent.action.equals("system-capture-consent")&&!request.result.isDone(),"screen capture first requests Android consent");
   try{NativeScreenCapture.request(ui,service.getFilesDir());throw new AssertionError();}catch(NativeBridgeServer.Failure busy){check(busy.code.equals("BUSY"),"one capture request at a time");}
   NativeScreenCapture.activityResult(new Activity(),Activity.RESULT_OK,new Intent("unrelated"));check(!request.authorized,"unrelated Activity cannot authorize capture");
   NativeScreenCapture.activityResult(ui,Activity.RESULT_CANCELED,null);check(request.result.isCompletedExceptionally(),"denial completes pending capture");
   request=NativeScreenCapture.request(ui,service.getFilesDir());NativeScreenCapture.activityResult(ui,Activity.RESULT_OK,new Intent("synthetic-grant"));
   check(request.authorized&&ui.lastService.action.equals("NativeCaptureService"),"authorized result launches capture foreground service");
   Intent first=ui.lastService;NativeScreenCapture.activityResult(ui,Activity.RESULT_OK,new Intent("reused-grant"));check(ui.lastService==first,"permission token cannot start twice");
   NativeScreenCapture.complete(request.id,NativeBridgePolicy.object("path","synthetic.png"));check(request.result.get().get("path").equals("synthetic.png"),"capture result returned once");
   NativeScreenCapture.activityResult(ui,Activity.RESULT_OK,new Intent("late"));check(ui.lastService==first,"late permission callback ignored");
   request=NativeScreenCapture.request(ui,service.getFilesDir());NativeBridge.detach(ui);check(request.result.isCompletedExceptionally(),"Activity destruction cancels pending authorization");
   NativeBridge.attach(ui);request=NativeScreenCapture.request(ui,service.getFilesDir());bridge.close();bridge=null;check(request.result.isCompletedExceptionally(),"bridge stop cancels pending capture");
   System.out.println("Native Android actions: "+checks+" foreground, clipboard, notification, sharing, intents, authorization and lifecycle checks passed (Android API fakes; no device).");
  }finally{if(bridge!=null)bridge.close();try(java.util.stream.Stream<Path>paths=Files.walk(directory)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception e){throw new RuntimeException(e);}});}}
 }
}
