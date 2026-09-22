package dev.dsh.nativeapp;

/**
 * 运行包更新决策：**纯 Java，无 Android 依赖，可离线测试**。
 *
 * <h3>为什么需要单独一个「修订号」</h3>
 * 原来的判据只有「哨兵文件是否匹配」：某个分片里的一个代表文件大小与摘要一致，
 * 就认为该分片是最新的。
 *
 * <p><b>这漏掉了一整类更新：只删文件的更新。</b>
 * 删掉某些文件后，其余文件的哨兵全部不变 —— 于是判定「已是最新」，
 * 那些本该消失的文件会**永远留在设备上**。
 * （实测例：运行包里的 {@code @img/} 目录是 Linux ARM64 的原生库，
 * 在 Android 上根本加载不了，占 27MB。删掉它属于典型的「只删不加」，
 * 靠哨兵永远发现不了。）
 *
 * <p>因此清单里增加一个**修订号**：内容有实质性变化（包括只有删除）时递增。
 * 修订号变了就整体重来 —— 因为无法知道究竟删了什么，
 * 单靠覆盖解压也无法移除文件。
 *
 * <p>日常的增量更新仍然走哨兵：只改一个工具时不必重下整个包。
 */
final class PayloadUpdate {

    /** 已是最新，无需下载。 */
    static final int ACTION_UPTODATE = 0;
    /** 增量：只下载哨兵不匹配的分片。 */
    static final int ACTION_INCREMENTAL = 1;
    /** 整体重来：清空后重新下载解压（修订号变化）。 */
    static final int ACTION_FULL = 2;

    private PayloadUpdate() { }

    /**
     * 决定本次的更新动作。
     *
     * @param remoteRevision  远端清单里的修订号
     * @param appliedRevision 本机已应用的修订号（首次为 0）
     * @param missingCount    哨兵不匹配的分片数
     */
    static int decide(int remoteRevision, int appliedRevision, int missingCount) {
        // 修订号变化 → 整体重来。不能用「增删差分」的方式处理：
        // 清单里没有记录删了哪些文件，只有重下才能保证设备上的内容与远端一致。
        if (remoteRevision > appliedRevision) return ACTION_FULL;
        // 修订号相同但哨兵不匹配 → 增量补齐（改动只涉及不变的那些分片）
        if (missingCount > 0) return ACTION_INCREMENTAL;
        return ACTION_UPTODATE;
    }

    /** 动作的可读说明，用于日志与界面提示。 */
    static String describe(int action, int partCount, int missingCount) {
        switch (action) {
            case ACTION_FULL:
                return "运行包内容有更新，正在重新下载（" + partCount + " 个分片）";
            case ACTION_INCREMENTAL:
                return "正在增量更新（" + missingCount + "/" + partCount + " 项）";
            default:
                return "运行包已是最新";
        }
    }

    /**
     * 是否需要清空目标目录。
     *
     * <p>整体重来时必须先删 —— 否则被移除的文件会残留，
     * 而这正是引入修订号要解决的问题。
     */
    static boolean needsWipe(int action) {
        return action == ACTION_FULL;
    }

    /**
     * 该分片是否需要下载。
     *
     * <p>整体重来时全部下载；增量时只下载哨兵不匹配的。
     */
    static boolean needsDownload(int action, boolean sentinelMatched) {
        if (action == ACTION_FULL) return true;
        if (action == ACTION_INCREMENTAL) return !sentinelMatched;
        return false;
    }
}
