package dev.dsh.nativeapp;
import android.app.Activity;import android.content.Intent;import android.net.Uri;import android.os.ParcelFileDescriptor;
import java.io.*;import java.nio.file.*;import java.util.*;
public class FileActionsAndroidTest {
 static int checks;static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
 interface Checked {void run()throws Exception;}
 static void refused(Checked operation)throws Exception{try{operation.run();throw new AssertionError("accepted invalid operation");}catch(FileNotFoundException expected){checks++;}}
 public static void main(String[] args)throws Exception {
  Path root=Files.createTempDirectory("dsh-file-target-");Activity activity=new Activity();activity.files=root.resolve("files").toFile();activity.cache=root.resolve("cache").toFile();
  File app=new File(activity.files,"dsh"),workspace=new File(app,"workspace");workspace.mkdirs();activity.cache.mkdirs();
  File file=new File(workspace,"中文 notes #1.docx");Files.write(file.toPath(),"synthetic document".getBytes("UTF-8"));
  List<File> roots=FileOps.writableRoots(app);UpdateProvider provider=new UpdateProvider();provider.context=activity;
  for(String action:new String[]{"open","share","edit"}){
   ExternalFileActions.launch(activity,file,action,roots);Intent intent=activity.lastIntent;boolean edit=action.equals("edit"),share=action.equals("share");
   check(intent.action.equals(edit?Intent.ACTION_EDIT:share?Intent.ACTION_SEND:Intent.ACTION_VIEW),"correct Android action");
   Uri uri=share?(Uri)intent.extras.get(Intent.EXTRA_STREAM):intent.uri;
   check(uri.value.startsWith("content://"+ProviderRoute.AUTHORITY+"/"+(edit?"e/":"f/")),"distinct scoped content URI");
   check(intent.clip!=null&&intent.clip.uri==uri&&(intent.flags&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0,"ClipData and read grant");
   check(((intent.flags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0)==edit,"write grant only for edit");
   check("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(intent.type),"Office MIME preserved");
   check(provider.getType(uri).equals(intent.type)&&provider.query(uri,null,null,null,null)!=null,"provider type and name query");
   check(provider.openFile(uri,"r").mode==ParcelFileDescriptor.MODE_READ_ONLY,"read descriptor available");
   if(edit){check(provider.openFile(uri,"rw").file.getCanonicalFile().equals(file.getCanonicalFile()),"edit opens actual original");check(provider.openFile(uri,"rwt").mode!=ParcelFileDescriptor.MODE_READ_ONLY,"external save mode supported");refused(()->provider.openFile(uri,"unknown"));}
   else refused(()->provider.openFile(uri,"rw"));
  }
  File outside=root.resolve("outside.txt").toFile();Files.write(outside.toPath(),new byte[]{1});
  for(File target:new File[]{outside,new File(workspace,"missing.txt"),workspace}){
   Uri uri=Uri.parse("content://"+ProviderRoute.AUTHORITY+"/e/"+ShareTargets.encode(target));refused(()->provider.openFile(uri,"rw"));
  }
  File secret=new File(workspace,".env");Files.write(secret.toPath(),new byte[]{1});Uri privateUri=Uri.parse("content://"+ProviderRoute.AUTHORITY+"/e/"+ShareTargets.encode(secret));refused(()->provider.openFile(privateUri,"rw"));
  Path link=workspace.toPath().resolve("escape.txt");Files.createSymbolicLink(link,outside.toPath());Uri escaped=Uri.parse("content://"+ProviderRoute.AUTHORITY+"/e/"+ShareTargets.encode(link.toFile()));refused(()->provider.openFile(escaped,"rw"));
  check(ShareTargets.mimeOf("a.xlsx").contains("spreadsheetml")&&ShareTargets.mimeOf("a.pptx").contains("presentationml"),"spreadsheet/slides recognized");
  Files.walk(root).sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new RuntimeException(e);}});
  System.out.println("Android file actions: "+checks+" intent, MIME, scoped write/read grant, provider save and path-boundary checks passed (Android API fakes; no device/apps).");
 }
}
