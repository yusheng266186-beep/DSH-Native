package dev.dsh.nativeapp;

/**
 * 版本号比较：**纯 Java，无 Android 依赖，可离线测试**。
 *
 * <p>抽出来的原因很简单：这段逻辑原本在「检查更新」和「网络诊断」里**各写了一份**。
 * 两份实现必然会在某次修改后产生分歧 —— 而分歧的后果是
 * 「更新提示说没有新版本」或「诊断说源有问题」，两者互相矛盾，很难查。
 *
 * <p>比较规则：按 {@code .} 分段逐段比数字，缺位补 0。
 * 因此 {@code 0.9.10 > 0.9.9}（按数值而非字典序），
 * {@code 1.0 == 1.0.0}（缺位补 0）。
 */
final class Version {

    private Version() { }

    /** 比较两个版本号：a &gt; b 返回正数，相等返回 0，a &lt; b 返回负数。 */
    static int compare(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        String[] x = a.trim().split("\\.");
        String[] y = b.trim().split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            long xi = i < x.length ? digit(x[i]) : 0L;
            long yi = i < y.length ? digit(y[i]) : 0L;
            if (xi != yi) return xi > yi ? 1 : -1;   // 只返回符号，避免相减溢出
        }
        return 0;
    }

    /** a 是否比 b 新。 */
    static boolean isNewer(String a, String b) {
        return compare(a, b) > 0;
    }

    /** 取一组版本里的最高值；全为空时返回 null。 */
    static String max(String... versions) {
        String best = null;
        if (versions == null) return null;
        for (String v : versions) {
            if (v == null || v.length() == 0) continue;
            if (best == null || compare(v, best) > 0) best = v;
        }
        return best;
    }

    /**
     * 取一个版本段的数值。
     *
     * <p>容忍非数字后缀（例如 {@code 0.19.6-beta}）—— 只取前导数字，
     * 取不到按 0 计。版本清单里目前都是纯数字，但**外部源返回什么无法保证**，
     * 不能因为一个格式差异就抛异常中断整个检查。
     *
     * <p>超出 long 范围时**饱和到 {@link Long#MAX_VALUE}**，而不是回退成 0 ——
     * 回退成 0 会让一个天文数字的版本被判成「比 1.0.0 还旧」，
     * 语义完全相反（实测踩过：断言 \`99999999999999.0.0 > 1.0.0\` 失败）。
     */
    private static long digit(String s) {
        if (s == null) return 0;
        s = s.trim();
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i == 0) return 0;
        String num = s.substring(0, i).replaceFirst("^0+(?=.)", "");
        if (num.length() > 18) return Long.MAX_VALUE;   // 远超 long，直接饱和
        try {
            return Long.parseLong(num);
        } catch (Throwable t) {
            return Long.MAX_VALUE;
        }
    }
}
