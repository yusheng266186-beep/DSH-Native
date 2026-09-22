package dev.dsh.nativeapp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 运行包更新决策：**纯 Java，无 Android 依赖，可离线测试**。
 *
 * <h3>为什么哨兵不够</h3>
 * 原来的判据只有「哨兵文件是否匹配」：某分片里的一个代表文件大小与摘要一致，
 * 就认为该分片是最新的。
 *
 * <p><b>这漏掉了一整类更新：只删文件的更新。</b> 删掉某些文件后，
 * 其余文件的哨兵全部不变 —— 于是判定「已是最新」，
 * 那些本该消失的文件会**永远留在设备上**。
 *
 * <h3>为什么不用「修订号变了就整包重来」</h3>
 * 那样虽然正确，但代价太大：删掉运行包里 27MB 用不上的原生库，
 * 却要用户重新下载全部 60MB（其中 40MB 是没变过的东西）。
 *
 * <p>所以改成**按分片**：
 * <ul>
 *   <li>每个分片有自己的 {@code revision}；只有它变了才重新下载它；</li>
 *   <li>分片可以声明 {@code remove} —— 一批要删除的相对路径。
 *       删文件这件事哨兵发现不了，靠这份显式清单来补。</li>
 * </ul>
 * 这样「删掉 27MB 无用文件」只需要重下 dsh 那一个分片，其余分片不受影响。
 */
final class PayloadUpdate {

    /** 一个分片的判定输入。 */
    static final class Part {
        final String name;
        /** 清单里该分片的修订号。 */
        final int revision;
        /** 哨兵是否匹配（即这个分片在本地看起来是完好的）。 */
        final boolean sentinelMatched;
        /** 处理该分片前要删除的相对路径。 */
        final List<String> remove;

        Part(String name, int revision, boolean sentinelMatched, List<String> remove) {
            this.name = name;
            this.revision = revision;
            this.sentinelMatched = sentinelMatched;
            this.remove = remove == null ? Collections.<String>emptyList() : remove;
        }
    }

    private PayloadUpdate() { }

    /**
     * 该分片是否需要处理（下载 + 解压）。
     *
     * <p>两种情况：
     * <ol>
     *   <li>哨兵不匹配 —— 文件缺失或被改动；</li>
     *   <li>修订号变高 —— 即使哨兵全对也要处理，
     *       因为这次变化可能只是删除文件（哨兵看不出来）。</li>
     * </ol>
     */
    static boolean needsWork(Part part, int appliedRevision) {
        if (part == null) return false;
        if (!part.sentinelMatched) return true;
        return part.revision > appliedRevision;
    }

    /**
     * 处理该分片前需要删除的相对路径。
     *
     * <p>只在「修订号变高」时返回 —— 哨兵不匹配属于新增/改动，
     * 不涉及删除，没必要动用户的文件。
     *
     * <p>路径必须经过 {@link #isSafeRelativePath} 过滤后才可用于删除：
     * 清单是从网络下载的，万一被篡改，一个 {@code ../../} 就能删掉配置目录。
     */
    static List<String> removalsFor(Part part, int appliedRevision) {
        List<String> out = new ArrayList<String>();
        if (part == null) return out;
        if (part.revision <= appliedRevision) return out;
        for (String p : part.remove) {
            if (isSafeRelativePath(p)) out.add(p);
        }
        return out;
    }

    /**
     * 这个相对路径是否允许删除。
     *
     * <p>拒绝：绝对路径、含 {@code ..}、含反斜杠、空、以 {@code .} 开头
     * 的敏感名（{@code .dsh} 配置目录等）。
     */
    static boolean isSafeRelativePath(String p) {
        if (p == null) return false;
        String s = p.trim();
        if (s.length() == 0 || s.length() > 300) return false;
        if (s.startsWith("/") || s.startsWith("\\")) return false;
        if (s.indexOf('\\') >= 0) return false;
        if (s.contains("..")) return false;
        if (s.indexOf('\0') >= 0) return false;
        // 不允许删到运行包之外的东西
        if (s.equals(".") || s.equals("./")) return false;
        String head = s.startsWith("./") ? s.substring(2) : s;
        if (head.startsWith(".dsh") || head.equals("cache") || head.startsWith("cache/")) {
            return false;
        }
        return true;
    }

    /** 需要处理的分片数。 */
    static int countNeedingWork(List<Part> parts, List<Integer> appliedRevisions) {
        int n = 0;
        for (int i = 0; i < parts.size(); i++) {
            int applied = i < appliedRevisions.size() ? appliedRevisions.get(i) : 0;
            if (needsWork(parts.get(i), applied)) n++;
        }
        return n;
    }

    /** 动作的可读说明，用于日志与界面提示。 */
    static String describe(int partCount, int needsWork) {
        if (needsWork <= 0) return "运行包已是最新";
        if (needsWork == partCount) return "正在下载运行包（" + partCount + " 个分片）";
        return "正在增量更新（" + needsWork + "/" + partCount + " 项）";
    }

    /** 已应用修订号的存储键（存进 SharedPreferences 的字符串集合）。 */
    static String revisionKey(String partName, int revision) {
        return partName + "=" + revision;
    }

    /** 从存储键里取回修订号；解析不出来时返回 0。 */
    static int parseRevision(String stored) {
        if (stored == null) return 0;
        int eq = stored.lastIndexOf('=');
        if (eq < 0 || eq >= stored.length() - 1) return 0;
        try {
            return Integer.parseInt(stored.substring(eq + 1).trim());
        } catch (Throwable t) {
            return 0;
        }
    }
}
