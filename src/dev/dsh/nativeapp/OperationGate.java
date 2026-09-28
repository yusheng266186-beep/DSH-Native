package dev.dsh.nativeapp;

/**
 * 互斥的维护任务闸门。
 *
 * <p>App 更新与运行包更新都会读写下载缓存并可能重启服务。用户连续点击、
 * 或从通知栏与设置页同时触发时，只允许第一项执行，避免两个后台线程互相
 * 删除临时文件、重复拉起安装器或在下载中途重启。</p>
 */
final class OperationGate {
    static final String APP_UPDATE = "App 更新";
    static final String PAYLOAD_UPDATE = "运行包更新";
    static final String PAYLOAD_ROLLBACK = "运行环境恢复";

    private String active;

    synchronized boolean tryStart(String operation) {
        if (operation == null || operation.trim().length() == 0 || active != null) {
            return false;
        }
        active = operation;
        return true;
    }

    synchronized void finish(String operation) {
        if (active != null && active.equals(operation)) active = null;
    }

    synchronized String active() {
        return active;
    }

    synchronized boolean isActive(String operation) {
        return active != null && active.equals(operation);
    }
}
