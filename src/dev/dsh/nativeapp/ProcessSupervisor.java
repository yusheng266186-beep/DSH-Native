package dev.dsh.nativeapp;

/**
 * 单进程监管器：纯 Java，无 Android 依赖，可离线测试。
 *
 * <p>Android 的 Activity 随时可能因主题、配置变化或系统回收而重建，
 * 因此长时间运行的 Node 进程不能由 Activity 字段持有。这个类只负责
 * 进程句柄的原子接管、存活判断与停止，Android Service 再负责通知和输出。
 */
final class ProcessSupervisor {

    private Process process;

    /** 接管一个新进程；已有的不同进程会先被停止，避免产生多个实例。 */
    synchronized void adopt(Process next) {
        if (next == null) throw new IllegalArgumentException("process 不能为空");
        if (process == next) return;
        Process previous = process;
        process = next;
        if (previous != null && alive(previous)) {
            try { previous.destroy(); } catch (Throwable ignored) { }
        }
    }

    /** 当前受管进程；仅供状态判断和输出关联，不得由界面直接销毁。 */
    synchronized Process current() {
        return process;
    }

    /** 当前是否有仍存活的受管进程。 */
    synchronized boolean isAlive() {
        return alive(process);
    }

    /** 进程自然退出时清除句柄；只清除同一个实例，防止误清新进程。 */
    synchronized boolean clear(Process expected) {
        if (process != expected) return false;
        process = null;
        return true;
    }

    /** 停止当前进程并立即清除句柄。 */
    synchronized boolean stop() {
        Process current = process;
        process = null;
        if (current == null) return false;
        try { current.destroy(); } catch (Throwable ignored) { }
        return true;
    }

    /** 使用 API 1 就存在的 exitValue 判断，兼容本项目的最低系统版本。 */
    static boolean alive(Process p) {
        if (p == null) return false;
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
