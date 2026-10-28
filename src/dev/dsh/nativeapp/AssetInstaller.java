package dev.dsh.nativeapp;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Content-addressed APK components, installed atomically. Independent of Activity and app version. */
final class AssetInstaller {
    interface Source { InputStream open(String path) throws IOException; }
    static final String INDEX = "runtime-assets.tsv";
    private static final String RECEIPT = ".native-assets";
    private final Source source;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<String, Entry>();
    final String identity;

    private static final class Entry {
        final String name, hash; final long size;
        Entry(String name, long size, String hash) { this.name = name; this.size = size; this.hash = hash; }
    }
    AssetInstaller(Source source) throws IOException {
        this.source = source;
        byte[] index;
        try (InputStream in = source.open(INDEX)) { index = readBounded(in, 1024 * 1024); }
        identity = digest(index);
        for (String line : new String(index, "UTF-8").split("\n")) {
            String[] row = line.split("\t", -1);
            if (row.length != 3 || !validName(row[0])
                    || !row[2].matches("[0-9a-f]{64}")) throw new IOException("Invalid APK asset index");
            long size;
            try { size = Long.parseLong(row[1]); } catch (NumberFormatException error) { throw new IOException("Invalid asset length", error); }
            if (size <= 0 || size > 256L * 1024 * 1024 || entries.put(row[0], new Entry(row[0], size, row[2])) != null)
                throw new IOException("Invalid or duplicate asset");
        }
        if (entries.isEmpty() || !entries.containsKey("node")) throw new IOException("Incomplete APK asset index");
    }
    static boolean validName(String name) {
        // The shipped Android C++ library is libc++_shared.so. A plus sign is a
        // filename character; traversal, absolute paths and separator aliases remain invalid.
        if (name == null || !name.matches("[A-Za-z0-9_.+-]+(?:/[A-Za-z0-9_.+-]+)*")) return false;
        for (String segment : name.split("/")) if (segment.equals(".") || segment.equals("..")) return false;
        return true;
    }
    boolean ready(File root) throws IOException {
        Map<String, String> proof = receipt(root);
        if (!identity.equals(proof.get("@index"))) return false;
        for (Entry e : entries.values()) { interrupted(); if (!matches(root, e, proof)) return false; }
        return true;
    }
    int install(File root) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create component directory");
        Map<String, String> proof = receipt(root);
        StringBuilder next = new StringBuilder("@index\t" + identity + "\n");
        int written = 0;
        for (Entry e : entries.values()) {
            interrupted();
            File out = target(root, e.name);
            if (!matches(root, e, proof) && !(out.isFile() && out.length() == e.size && e.hash.equals(hash(out)))) {
                File parent = out.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create component parent");
                File temp = File.createTempFile(".asset-", ".tmp", parent);
                try {
                    MessageDigest md = sha256(); long length = 0;
                    try (InputStream in = source.open("payload/" + e.name); FileOutputStream os = new FileOutputStream(temp)) {
                        byte[] buffer = new byte[262144]; int n;
                        while ((n = in.read(buffer)) != -1) {
                            interrupted(); length += n;
                            if (length > e.size) throw new IOException("APK asset exceeds index: " + e.name);
                            md.update(buffer, 0, n); os.write(buffer, 0, n);
                        }
                        os.getFD().sync();
                    }
                    if (length != e.size || !hex(md.digest()).equals(e.hash)) throw new IOException("APK asset digest mismatch: " + e.name);
                    if (!temp.renameTo(out)) throw new IOException("Cannot publish component: " + e.name);
                    written++;
                } finally { if (temp.exists() && !temp.delete()) throw new IOException("Cannot remove component temporary file"); }
            }
            next.append(e.name).append('\t').append(proof(out, e)).append('\n');
        }
        atomic(root, RECEIPT, next.toString());
        return written;
    }
    private boolean matches(File root, Entry e, Map<String, String> proof) throws IOException {
        File file = target(root, e.name);
        if (!file.isFile() || file.length() != e.size || !proof(file, e).equals(proof.get(e.name))) return false;
        // Scripts are cheap to verify; large immutable binaries use their private installation receipt.
        return e.size >= 1024 * 1024 || e.hash.equals(hash(file));
    }
    private static String proof(File file, Entry e) { return e.hash + ":" + file.length() + ":" + file.lastModified(); }
    private static File target(File root, String path) throws IOException {
        File base = root.getCanonicalFile(), file = new File(base, path);
        if (!file.getAbsolutePath().equals(file.getCanonicalPath()) || !file.getPath().startsWith(base.getPath() + File.separator))
            throw new IOException("Component path contains a link or escapes root");
        return file;
    }
    private static Map<String, String> receipt(File root) throws IOException {
        File file = target(root, RECEIPT);
        Map<String, String> result = new HashMap<String, String>();
        if (!file.isFile()) return result;
        try (InputStream in = new FileInputStream(file)) {
            for (String line : new String(readBounded(in, 1024 * 1024), "UTF-8").split("\n")) {
                int tab = line.indexOf('\t'); if (tab > 0) result.put(line.substring(0, tab), line.substring(tab + 1));
            }
        }
        return result;
    }
    static void atomic(File root, String name, String value) throws IOException {
        File out = target(root, name), temp = File.createTempFile(".receipt-", ".tmp", root);
        try {
            try (FileOutputStream os = new FileOutputStream(temp)) { os.write(value.getBytes("UTF-8")); os.getFD().sync(); }
            interrupted();
            if (!temp.renameTo(out)) throw new IOException("Cannot publish installation receipt");
        } finally { if (temp.exists() && !temp.delete()) throw new IOException("Cannot remove installation receipt temporary file"); }
    }
    private static void interrupted() throws InterruptedIOException { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Component preparation cancelled"); }
    private static byte[] readBounded(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) != -1) { if (out.size() + n > max) throw new IOException("Component index exceeds limit"); out.write(buf, 0, n); }
        return out.toByteArray();
    }
    private static MessageDigest sha256() { try { return MessageDigest.getInstance("SHA-256"); } catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); } }
    private static String digest(byte[] data) { return hex(sha256().digest(data)); }
    private static String hash(File file) throws IOException {
        MessageDigest md = sha256();
        try (InputStream in = new FileInputStream(file)) { byte[] buffer = new byte[262144]; int n; while ((n = in.read(buffer)) != -1) { interrupted(); md.update(buffer, 0, n); } }
        return hex(md.digest());
    }
    private static String hex(byte[] data) { StringBuilder out = new StringBuilder(); for (byte b : data) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString(); }
}
