package dev.dsh.nativeapp;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ModelEffortUiTest {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--dump-client".equals(args[0])) {
            String source = new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8);
            String patched = ModelEffortUi.patch(source);
            if (patched == null || !patched.equals(ModelEffortUi.patch(patched)))
                throw new AssertionError("Published composer patch must apply idempotently");
            System.out.print(patched);
            return;
        }
        if (ModelEffortUi.patch("changed upstream source") != null)
            throw new AssertionError("Unrecognized source must remain untouched");
        System.out.println("TOTAL: 1 pass / 0 fail");
    }
}
