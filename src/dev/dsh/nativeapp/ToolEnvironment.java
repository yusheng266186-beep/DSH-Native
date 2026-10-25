package dev.dsh.nativeapp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Repair relocatable scripts inside the managed toolchain, preserving user packages. */
final class ToolEnvironment {
    private static final String TERMUX = "/data/data/com.termux/files/usr/bin/";
    private ToolEnvironment() { }

    static String path(File root) {
        return new File(root, ".native-bin").getAbsolutePath() + ":" + new File(root, "tools/bin").getAbsolutePath()
                + ":" + root.getAbsolutePath() + ":/system/bin:/system/xbin";
    }

    static int repair(File root) throws IOException {
        root = root.getCanonicalFile();
        File tools = new File(root, "tools").getCanonicalFile();
        if (!tools.equals(new File(root, "tools"))) throw new IOException("Tool directory leaves managed runtime");
        File bin = new File(tools, "bin");
        File owned = new File(root, ".native-bin");
        if ((!owned.isDirectory() && !owned.mkdirs()) || !owned.getCanonicalFile().equals(owned.getAbsoluteFile()))
            throw new IOException("Cannot prepare App command directory");
        File temporary = new File(root, "tmp");
        if ((!temporary.isDirectory() && !temporary.mkdirs()) || !temporary.getCanonicalFile().equals(temporary.getAbsoluteFile()))
            throw new IOException("Cannot prepare App temporary directory");
        int fixed = 0;
        fixed += wrapper(new File(owned, "node"), new File(root, "node"), null);
        fixed += wrapper(new File(owned, "npm"), new File(root, "node"), new File(tools, "lib/node_modules/npm/bin/npm-cli.js"));
        fixed += wrapper(new File(owned, "npx"), new File(root, "node"), new File(tools, "lib/node_modules/npm/bin/npx-cli.js"));
        fixed += wrapper(new File(owned, "dsh-android"), new File(root, "node"), new File(root, "android-bridge.cjs"));
        File[] entries = bin.listFiles();
        if (entries == null) throw new IOException("Cannot inspect tool scripts");
        List<File> visited = new ArrayList<File>();
        for (File entry : entries) {
            File file = entry.getCanonicalFile();
            if (!file.getPath().startsWith(tools.getPath() + File.separator) || !file.isFile()
                    || file.length() > 1024 * 1024 || visited.contains(file)) continue;
            visited.add(file);
            byte[] bytes;
            try (java.io.FileInputStream input = new java.io.FileInputStream(file)) {
                byte[] head = new byte[(int) Math.min(file.length(), 512)];
                int length = input.read(head);
                if (length < 3 || head[0] != '#' || head[1] != '!') continue;
                int line = 0;
                while (line < length && head[line] != '\n') line++;
                String first = new String(head, 0, line, StandardCharsets.UTF_8);
                String next = shebang(first, root);
                if (next.equals(first)) continue;
                bytes = new byte[(int) file.length()];
                int offset = 0;
                System.arraycopy(head, 0, bytes, 0, length);
                offset = length;
                while (offset < bytes.length) {
                    int count = input.read(bytes, offset, bytes.length - offset);
                    if (count < 0) throw new IOException("Tool script changed during repair");
                    offset += count;
                }
                byte[] prefix = next.getBytes(StandardCharsets.UTF_8);
                byte[] result = new byte[prefix.length + bytes.length - line];
                System.arraycopy(prefix, 0, result, 0, prefix.length);
                System.arraycopy(bytes, line, result, prefix.length, bytes.length - line);
                replace(file, result, file.canExecute()); fixed++;
            }
        }
        return fixed;
    }

    static String shebang(String first, File root) throws IOException {
        if (!first.startsWith("#!")) return first;
        String interpreterLine = first.substring(2).trim();
        String command;
        if (interpreterLine.startsWith(TERMUX)) command = interpreterLine.substring(TERMUX.length());
        else if (interpreterLine.startsWith("/usr/bin/")) command = interpreterLine.substring("/usr/bin/".length());
        else if (interpreterLine.startsWith("/bin/")) command = interpreterLine.substring("/bin/".length());
        else return first;
        String executable = command.split("\\s+", 2)[0];
        if (!executable.matches("[A-Za-z0-9_.+-]+")) return first;
        File interpreter = new File(root, "tools/bin/" + executable);
        if (!interpreter.getCanonicalFile().getPath().startsWith(new File(root, "tools").getAbsolutePath() + File.separator)
                || !interpreter.isFile()) return first;
        return "#!" + interpreter.getAbsolutePath() + command.substring(executable.length());
    }

    private static int wrapper(File target, File executable, File script) throws IOException {
        if (!executable.isFile() || (script != null && !script.isFile())) return 0;
        String text = "#!/system/bin/sh\n# dsh-native-command-v1\nexec " + quote(executable.getAbsolutePath())
                + (script == null ? "" : " " + quote(script.getAbsolutePath())) + " \"$@\"\n";
        // .native-bin is App-owned; retain any preexisting custom entry.
        if (!target.getCanonicalFile().equals(target.getAbsoluteFile())) return 0;
        if (target.isFile()) {
            byte[] prefix = new byte[(int) Math.min(target.length(), 512)];
            try (java.io.FileInputStream input = new java.io.FileInputStream(target)) {
                int count = input.read(prefix);
                if (count <= 0 || !new String(prefix, 0, count, StandardCharsets.UTF_8).contains("# dsh-native-command-v1")) return 0;
            }
            if (ProjectModelSettings.readFile(target).equals(text)) return 0;
        }
        replace(target, text.getBytes(StandardCharsets.UTF_8), true);
        return 1;
    }

    private static String quote(String text) { return "'" + text.replace("'", "'\\''") + "'"; }

    private static void replace(File target, byte[] bytes, boolean executable) throws IOException {
        File temporary = File.createTempFile(".dsh-tool-", ".next", target.getParentFile());
        try {
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(temporary)) {
                output.write(bytes); output.getFD().sync();
            }
            if (executable && !temporary.setExecutable(true, false)) throw new IOException("Cannot prepare executable tool");
            if (!temporary.renameTo(target)) throw new IOException("Cannot publish repaired tool");
        } finally { if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit(); }
    }
}
