package dev.dsh.nativeapp;
import java.nio.file.*;
public class FileActionsUiTest {
 public static void main(String[] args)throws Exception {
  if(args.length==2){String original=Files.readString(Paths.get(args[1]));boolean preview="--dump-preview".equals(args[0]);String patched=preview?FileActionsUi.preview(original):FileActionsUi.patch(original);if(patched==null||!patched.equals(preview?FileActionsUi.preview(patched):FileActionsUi.patch(patched)))throw new AssertionError("delivery patch changed");System.out.print(patched);return;}
  if(FileActionsUi.patch("unknown")!=null)throw new AssertionError("unknown source");
  String source="actionUrl: presentedFileUrl(sessionId, file.seq, file.index),\nactionUrl: changedFileUrl(sessionId, seq, index),";
  String patched=FileActionsUi.patch(source);
  if(patched==null||!patched.contains("absolutePath")||!patched.contains("cwd + '/' + file.path")||!patched.equals(FileActionsUi.patch(patched)))throw new AssertionError("patch/idempotence");
  if(FileActionsUi.patch(source+source)!=null)throw new AssertionError("ambiguous source");
  System.out.println("FileActionsUiTest: 3 checks passed");
 }
}
