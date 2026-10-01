package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PayloadManifest 的离线测试。
 *
 * 重点是把「一份从网络下载的 manifest 被篡改后会发生什么」逐条钉死：
 * 分片名、哨兵路径、remove 路径都会被用来定位或删除本地文件，
 * 任何一个能逃出目标目录的取值都必须被拒绝。
 *
 * 这段判定此前整块写在 MainActivity 里，**一行测试都跑不了**（org.json 只有
 * Android 有）。抽成纯逻辑类后才有了这些用例。
 */
public class PayloadManifestTest {
    static int pass = 0, fail = 0;

    static void check(String what, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  OK   " + what); }
        else { fail++; System.out.println("  FAIL " + what + "  -> " + detail); }
    }

    static String sha() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++) sb.append('a');
        return sb.toString();
    }

    static PayloadManifest.Part part(String name, String target) {
        return PayloadManifest.Part.of(name, target, 1024L, sha(),
                "lib/bin.js", 10L, sha());
    }

    static List<PayloadManifest.Part> list(PayloadManifest.Part... parts) {
        return new ArrayList<PayloadManifest.Part>(Arrays.asList(parts));
    }

    public static void main(String[] args) {
        System.out.println("=== 1. 正常清单必须通过 ===");
        check("two valid parts accepted",
                PayloadManifest.validate(1, list(
                        part("dsh.tar.zst", "dsh"), part("tools-base.tar.zst", "tools"))) == null,
                "rejected a valid manifest");

        System.out.println("=== 2. 结构版本与分片数量 ===");
        check("version 0 rejected",
                PayloadManifest.validate(0, list(part("a.tar.zst", "dsh"))) != null, "accepted");
        check("version 101 rejected",
                PayloadManifest.validate(101, list(part("a.tar.zst", "dsh"))) != null, "accepted");
        check("empty parts rejected", PayloadManifest.validate(1, list()) != null, "accepted");
        check("21 parts rejected", PayloadManifest.validate(1, manyParts(21)) != null, "accepted");
        check("20 parts accepted", PayloadManifest.validate(1, manyParts(20)) == null, "rejected");
        check("null parts rejected", PayloadManifest.validate(1, null) != null, "accepted");
        check("null element rejected",
                PayloadManifest.validate(1, Arrays.<PayloadManifest.Part>asList((PayloadManifest.Part) null)) != null,
                "accepted");

        System.out.println("=== 3. 分片名：资产名规则 ===");
        String[] badNames = {"../evil.tar.zst", "sub/dir.tar.zst", "with space.tar.zst",
                ".hidden.tar.zst", "a\\b.tar.zst", "no-suffix"};
        for (String bad : badNames) {
            check("name rejected: " + bad,
                    PayloadManifest.validate(1, list(part(bad, "dsh"))) != null, "ACCEPTED");
        }

        System.out.println("=== 4. 分片名重复会让「哪些已就绪」有歧义 ===");
        check("duplicate names rejected",
                PayloadManifest.validate(1, list(
                        part("dsh.tar.zst", "dsh"), part("dsh.tar.zst", "tools"))) != null,
                "accepted");

        System.out.println("=== 5. 分片目标白名单 ===");
        check("unknown target rejected",
                PayloadManifest.validate(1, list(part("a.tar.zst", "etc"))) != null, "accepted");
        check("dsh accepted", PayloadManifest.validate(1, list(part("a.tar.zst", "dsh"))) == null, "rejected");
        check("tools accepted", PayloadManifest.validate(1, list(part("a.tar.zst", "tools"))) == null, "rejected");

        System.out.println("=== 6. 分片大小（写满磁盘的防线）===");
        PayloadManifest.Part zero = part("a.tar.zst", "dsh");
        zero.size = 0L;
        check("zero size rejected", PayloadManifest.validate(1, list(zero)) != null, "accepted");
        PayloadManifest.Part huge = part("a.tar.zst", "dsh");
        huge.size = PayloadManifest.MAX_PART_SIZE + 1;
        check("oversize rejected", PayloadManifest.validate(1, list(huge)) != null, "accepted");
        PayloadManifest.Part unpacked = part("a.tar.zst", "dsh").withUnpackedSize(
                PayloadManifest.MAX_UNPACKED_SIZE + 1);
        check("oversize unpacked rejected", PayloadManifest.validate(1, list(unpacked)) != null, "accepted");
        check("absent unpacked_size is fine",
                PayloadManifest.validate(1, list(part("a.tar.zst", "dsh").withUnpackedSize(-1L))) == null,
                "rejected a normal part");

        System.out.println("=== 7. 摘要格式 ===");
        PayloadManifest.Part badSha = part("a.tar.zst", "dsh");
        badSha.sha256 = "not-a-digest";
        check("bad part digest rejected", PayloadManifest.validate(1, list(badSha)) != null, "accepted");
        PayloadManifest.Part badSentinel = part("a.tar.zst", "dsh");
        badSentinel.sentinelSha256 = "zz";
        check("bad sentinel digest rejected", PayloadManifest.validate(1, list(badSentinel)) != null, "accepted");
        PayloadManifest.Part negSentinel = part("a.tar.zst", "dsh");
        negSentinel.sentinelSize = -1L;
        check("negative sentinel size rejected", PayloadManifest.validate(1, list(negSentinel)) != null, "accepted");

        System.out.println("=== 8. 哨兵路径逃逸 ===");
        String[] escapes = {"../../etc/passwd", "/etc/passwd", "a/../../b", "..\\win"};
        for (String escape : escapes) {
            PayloadManifest.Part p = part("a.tar.zst", "dsh");
            p.sentinelPath = escape;
            check("sentinel path rejected: " + escape,
                    PayloadManifest.validate(1, list(p)) != null, "ACCEPTED");
        }

        System.out.println("=== 9. remove 列表逃逸（后果最严重：真的会删文件）===");
        PayloadManifest.Part evil = part("a.tar.zst", "dsh").withRemovals(
                Arrays.asList("lib/old.js", "../../../sdcard/DSHNative/credentials.yaml"));
        check("escaping remove rejected", PayloadManifest.validate(1, list(evil)) != null, "ACCEPTED");
        check("safe removals accepted",
                PayloadManifest.validate(1, list(part("a.tar.zst", "dsh").withRemovals(
                        Arrays.asList("lib/old.js", "tools/bin/gawk")))) == null, "rejected");
        check("empty removals accepted",
                PayloadManifest.validate(1, list(part("a.tar.zst", "dsh").withRemovals(
                        new ArrayList<String>()))) == null, "rejected");
        check("absent removals accepted",
                PayloadManifest.validate(1, list(part("a.tar.zst", "dsh").withRemovals(null))) == null, "rejected");

        System.out.println("=== 10. 清单文件大小 ===");
        check("empty file rejected", PayloadManifest.validateManifestFileSize(0) != null, "accepted");
        check("oversize file rejected",
                PayloadManifest.validateManifestFileSize(PayloadManifest.MAX_MANIFEST_BYTES + 1) != null,
                "accepted");
        check("normal size accepted",
                PayloadManifest.validateManifestFileSize(4096) == null, "rejected");

        System.out.println();
        System.out.println("PayloadManifestTest: TOTAL: " + pass + " pass / " + fail + " fail");
        if (fail > 0) System.exit(1);
    }

    static List<PayloadManifest.Part> manyParts(int n) {
        List<PayloadManifest.Part> out = new ArrayList<PayloadManifest.Part>();
        for (int i = 0; i < n; i++) out.add(part("part-" + i + ".tar.zst", "dsh"));
        return out;
    }
}
