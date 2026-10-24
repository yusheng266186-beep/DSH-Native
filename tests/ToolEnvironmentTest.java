package dev.dsh.nativeapp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
public class ToolEnvironmentTest {
    private static int checks;
    private static void check(boolean value) { if(!value)throw new AssertionError("tool environment "+checks);checks++; }
    private static void write(Path path,String value)throws Exception{Files.createDirectories(path.getParent());Files.write(path,value.getBytes(StandardCharsets.UTF_8));}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("dsh-tools-").toRealPath();
        try {
            write(root.resolve("node"),"node");
            write(root.resolve("tools/bin/bash"),"binary placeholder");
            write(root.resolve("tools/bin/env"),"binary placeholder");
            write(root.resolve("tools/lib/node_modules/npm/bin/npm-cli.js"),"#!/data/data/com.termux/files/usr/bin/env node\nconsole.log('preserved');\n");
            write(root.resolve("tools/lib/node_modules/npm/bin/npx-cli.js"),"// npx\n");
            Files.createSymbolicLink(root.resolve("tools/bin/npm"),root.resolve("tools/lib/node_modules/npm/bin/npm-cli.js"));
            Path script=root.resolve("tools/bin/example");
            write(script,"#!/data/data/com.termux/files/usr/bin/bash\necho unchanged\n");script.toFile().setExecutable(true);
            write(root.resolve("tools/bin/unknown"),"#!/data/data/com.termux/files/usr/bin/perl\nretained\n");
            write(root.resolve("outside"),"#!/data/data/com.termux/files/usr/bin/bash\nprotected\n");
            Files.createSymbolicLink(root.resolve("tools/bin/external"),root.resolve("outside"));
            check(ToolEnvironment.repair(root.toFile())==5);
            check(ToolEnvironment.repair(root.toFile())==0);
            check(Files.isSymbolicLink(root.resolve("tools/bin/npm")));
            check(new String(Files.readAllBytes(script),StandardCharsets.UTF_8).equals("#!"+root+"/tools/bin/bash\necho unchanged\n"));
            check(script.toFile().canExecute());
            check(new String(Files.readAllBytes(root.resolve("outside")),StandardCharsets.UTF_8).contains("com.termux"));
            check(new String(Files.readAllBytes(root.resolve("tools/bin/unknown")),StandardCharsets.UTF_8).contains("com.termux"));
            check(new String(Files.readAllBytes(root.resolve("tools/lib/node_modules/npm/bin/npm-cli.js")),StandardCharsets.UTF_8).endsWith("console.log('preserved');\n"));
            check(Files.isDirectory(root.resolve("tmp")));
            check(ToolEnvironment.path(root.toFile()).startsWith(root+"/.native-bin:"));
            check(ToolEnvironment.shebang("#!/usr/bin/env node",root.toFile()).equals("#!"+root+"/tools/bin/env node"));
            Path wrapper=root.resolve(".native-bin/npm");
            check(new String(Files.readAllBytes(wrapper),StandardCharsets.UTF_8).contains(" \"$@\"\n"));
            write(wrapper,"#!/system/bin/sh\n# user command\n");
            check(ToolEnvironment.repair(root.toFile())==0);
            check(new String(Files.readAllBytes(wrapper),StandardCharsets.UTF_8).contains("# user command"));
            Files.delete(root.resolve(".native-bin/npx"));
            Files.createSymbolicLink(root.resolve(".native-bin/npx"),root.resolve("outside"));
            check(ToolEnvironment.repair(root.toFile())==0);
            check(Files.isSymbolicLink(root.resolve(".native-bin/npx")));
            System.out.println("TOTAL: "+checks+" pass / 0 fail");
        } finally { try(java.util.stream.Stream<Path> paths=Files.walk(root)){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.delete(path);}catch(Exception error){throw new RuntimeException(error);}});} }
    }
}
