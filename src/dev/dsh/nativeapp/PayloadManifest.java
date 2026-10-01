package dev.dsh.nativeapp;

import java.util.HashSet;
import java.util.List;

/**
 * 运行包清单的**结构与取值校验**（纯逻辑，可离线测试）。
 *
 * <p>为什么抽出来：这段逻辑原本整块写在 {@code MainActivity.validatePayloadManifest} 里，
 * 二十多处分支，却一行测试都跑不了 —— 而它决定「一份从网络下载的 manifest 能不能信」。
 * 清单里的分片名、哨兵路径和 remove 路径都会被用来**定位和删除本地文件**，
 * 一个 {@code ../} 就能越出目标目录；分片大小失控则可能把磁盘写满。
 * 这些后果不该只靠人眼守。
 *
 * <p>为什么不接收 JSON：{@code org.json} 由 Android 提供，普通 JVM 上没有，
 * 一旦直接依赖它，这个类就再也测不了（项目里所有纯逻辑类都不碰 android/org.json）。
 * 所以这里只接收**已经取出来的标量值**，由调用方负责解析。
 *
 * <p>返回 {@code null} 表示通过，否则是给用户看的中文原因。
 */
final class PayloadManifest {

    /** 单个分片允许的最大压缩体积（512 MiB）。 */
    static final long MAX_PART_SIZE = 512L * 1024L * 1024L;
    /** 单个分片允许的最大展开体积（8 GiB）。 */
    static final long MAX_UNPACKED_SIZE = 8L * 1024L * 1024L * 1024L;
    /** 清单文件本身的大小区间。 */
    static final long MIN_MANIFEST_BYTES = 1L;
    static final long MAX_MANIFEST_BYTES = 256L * 1024L;
    /** 结构版本与分片数量的合理区间。 */
    static final int MIN_VERSION = 1;
    static final int MAX_VERSION = 100;
    static final int MIN_PARTS = 1;
    static final int MAX_PARTS = 20;

    private static final String TARGET_DSH = "dsh";
    private static final String TARGET_TOOLS = "tools";

    private PayloadManifest() { }

    /** 一个分片在结构上被允许的全部字段。 */
    static final class Part {
        String name;
        String target;
        long size;
        /** -1 表示清单里没有这个字段。 */
        long unpackedSize = -1L;
        String sha256;
        String sentinelPath;
        long sentinelSize;
        String sentinelSha256;
        /** 清单里的 remove 列表；null 表示没有该字段。 */
        List<String> removals;

        static Part of(String name, String target, long size, String sha256,
                       String sentinelPath, long sentinelSize, String sentinelSha256) {
            Part p = new Part();
            p.name = name;
            p.target = target;
            p.size = size;
            p.sha256 = sha256;
            p.sentinelPath = sentinelPath;
            p.sentinelSize = sentinelSize;
            p.sentinelSha256 = sentinelSha256;
            return p;
        }

        Part withUnpackedSize(long value) {
            this.unpackedSize = value;
            return this;
        }

        Part withRemovals(List<String> value) {
            this.removals = value;
            return this;
        }
    }

    /** 清单文件本身的大小是否合理。 */
    static String validateManifestFileSize(long length) {
        if (length < MIN_MANIFEST_BYTES || length > MAX_MANIFEST_BYTES) {
            return "文件大小异常";
        }
        return null;
    }

    /**
     * 校验整份清单的结构。
     *
     * @param version 结构版本号
     * @param parts   分片列表；元素为 null 视为结构损坏
     * @return 通过返回 null，否则返回中文原因
     */
    static String validate(int version, List<Part> parts) {
        if (version < MIN_VERSION || version > MAX_VERSION) return "结构版本异常";
        if (parts == null || parts.size() < MIN_PARTS || parts.size() > MAX_PARTS) {
            return "分片数量异常";
        }
        HashSet<String> names = new HashSet<String>();
        for (Part part : parts) {
            if (part == null) return "分片结构损坏";
            String problem = validatePart(part, names);
            if (problem != null) return problem;
        }
        return null;
    }

    private static String validatePart(Part part, HashSet<String> names) {
        if (!PayloadUpdate.isSafeAssetName(part.name)) return "分片名不安全：" + part.name;
        // 重名会让「哪些分片已就绪」的判定出现歧义，必须拒绝。
        if (!names.add(part.name)) return "分片名重复：" + part.name;
        if (!TARGET_DSH.equals(part.target) && !TARGET_TOOLS.equals(part.target)) {
            return "分片目标不允许：" + part.target;
        }
        if (part.size <= 0 || part.size > MAX_PART_SIZE) return "分片大小异常：" + part.name;
        if (part.unpackedSize > 0 && part.unpackedSize > MAX_UNPACKED_SIZE) {
            return "分片展开大小异常：" + part.name;
        }
        if (!PayloadUpdate.isSha256(part.sha256)) return "分片摘要格式错误：" + part.name;
        if (!PayloadUpdate.isSafeRelativePath(part.sentinelPath)) {
            return "哨兵路径不安全：" + part.name;
        }
        if (part.sentinelSize < 0 || !PayloadUpdate.isSha256(part.sentinelSha256)) {
            return "哨兵信息异常：" + part.name;
        }
        if (part.removals != null) {
            for (String path : part.removals) {
                // remove 列表会被真的拿去删文件，路径逃逸的后果最严重。
                if (!PayloadUpdate.isSafeRelativePath(path)) {
                    return "删除路径不安全：" + part.name;
                }
            }
        }
        return null;
    }
}
