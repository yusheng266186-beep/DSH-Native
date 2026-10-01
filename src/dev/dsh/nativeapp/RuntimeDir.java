package dev.dsh.nativeapp;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 运行目录轮换时的删除守卫（纯逻辑，可离线测试）。
 *
 * <p>抽出来的原因：这段判定原先写在 {@code MainActivity.deleteRuntimeChild} 里，
 * 是**唯一挡在「递归删除」与用户数据之间的那道门**，却一行测试都跑不了。
 * 它一旦被放松，后果不是报错而是**用户数据被删掉**。
 *
 * <p>三道独立的防线，缺一不可：
 * <ol>
 *   <li><b>名字白名单</b>：只有轮换过程会产生的固定目录名可删。
 *       `.dsh`、`cache`、`credentials.yaml` 这些都不在名单里。</li>
 *   <li><b>直属父目录</b>：目标必须是 root 的**直接子目录**，
 *       不能是孙级，也不能是 root 自己。</li>
 *   <li><b>规范路径相等</b>：用 {@code getCanonicalFile()} 比较，
 *       否则一个指向别处的软链就能让「看起来在 root 下」的目标实际在别处。</li>
 * </ol>
 */
final class RuntimeDir {

    /** 轮换过程会产生的目录名，其余一律不许删。 */
    private static final Set<String> DELETABLE = new HashSet<String>(Arrays.asList(
            "dsh", "tools", "payload-rollback", "payload-rollback.next",
            "payload-rollback.old", ".rollback-current-dsh", ".rollback-current-tools"));

    private RuntimeDir() { }

    /** 这个目录名是否在允许删除的白名单里。 */
    static boolean isDeletableName(String name) {
        return name != null && DELETABLE.contains(name);
    }

    /** 可删除的目录名集合（供测试与文档核对）。 */
    static Set<String> deletableNames() {
        return Collections.unmodifiableSet(DELETABLE);
    }

    /**
     * 删除前的守卫。
     *
     * @return 通过返回 null；否则是拒绝原因
     */
    static String checkDeletable(File target, File root) {
        if (target == null || root == null) return "路径为空";
        String name = target.getName();
        if (!isDeletableName(name)) return "拒绝删除运行目录边界外的路径";
        File parent = target.getParentFile();
        if (parent == null) return "拒绝删除运行目录边界外的路径";
        try {
            if (!parent.getCanonicalFile().equals(root.getCanonicalFile())) {
                return "拒绝删除运行目录边界外的路径";
            }
        } catch (IOException e) {
            // 取不到规范路径就无法确认边界，宁可拒绝。
            return "拒绝删除运行目录边界外的路径";
        }
        return null;
    }

    /**
     * 删除一个运行目录。
     *
     * @throws IOException 被守卫拒绝、或删除失败
     */
    static void delete(File target, File root) throws IOException {
        if (target == null || root == null || !target.exists()) return;
        String refusal = checkDeletable(target, root);
        if (refusal != null) throw new IOException(refusal);
        String error = FileOps.delete(target, Collections.singletonList(root));
        if (error != null || target.exists()) {
            throw new IOException("无法清理维护目录 " + target.getName()
                    + (error == null ? "" : "（" + error + "）"));
        }
    }
}
