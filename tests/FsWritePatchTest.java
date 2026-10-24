package dev.dsh.nativeapp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
public class FsWritePatchTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "--dump-module".equals(args[0])) {
            String source=new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8);
            String helper=new String(Files.readAllBytes(Paths.get(args[2])), StandardCharsets.UTF_8);
            String next=FsWritePatch.patch(source,helper);
            if(next==null || !next.equals(FsWritePatch.patch(next,helper)))throw new AssertionError("actual filesystem patch");
            System.out.print(next); return;
        }
        if(FsWritePatch.patch("unknown","invalid")!=null)throw new AssertionError("unsafe filesystem patch");
        System.out.println("TOTAL: 1 pass / 0 fail");
    }
}
