package dev.dsh.nativeapp;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 原生界面的轻量双语层。
 *
 * <p>DSH 网页有自己的语言设置；本类只负责 App 原生外壳（引导、工具入口、
 * 设置与辅助面板）。它刻意不依赖 Android，因此语言解析与回退规则可以在普通
 * JVM 中回归测试。</p>
 */
public final class UiText {
    public static final String AUTO = "auto";
    public static final String ZH = "zh";
    public static final String EN = "en";

    private static volatile String language = ZH;
    private static final Map<String, String> ENGLISH = new LinkedHashMap<String, String>();

    static {
        put("设置", "Settings");
        put("工具", "Tools");
        put("工具与设置", "Tools & settings");
        put("账号与模型", "Account & model");
        put("显示与语言", "Display & language");
        put("更新与维护", "Updates & maintenance");
        put("数据与扩展", "Data & extensions");
        put("诊断与日志", "Diagnostics & logs");
        put("项目与工作区", "Projects & workspace");
        put("管理项目", "Manage projects");
        put("新建项目", "New project");
        put("新建并切换", "Create & switch");
        put("已有项目", "Existing projects");
        put("默认工作区", "Default workspace");
        put("浏览当前项目", "Browse current project");
        put("正在使用", "Active");
        put("切换", "Switch");
        put("项目名需为 1 至 48 个字符，且不能包含路径符号",
                "Use 1–48 characters and no path separators.");
        put("无法创建项目目录", "Could not create the project directory.");
        put("工作区不可用", "Workspace unavailable");
        put("返回", "Back");
        put("完成", "Done");
        put("应用", "Apply");
        put("开始配置", "Start setup");
        put("欢迎使用 DeepSeek Harness", "Welcome to DeepSeek Harness");
        put("首次使用只需完成三步", "Get started in three steps");
        put("选择界面语言", "Choose interface language");
        put("跟随系统", "Use system language");
        put("简体中文", "Simplified Chinese");
        put("英文", "English");
        put("界面语言", "Interface language");
        put("语言已切换", "Language changed");
        put("原生工具界面使用此语言；DSH 网页语言可在网页设置中单独调整。",
                "This controls the native tools. The DSH web language can be changed separately in web settings.");

        put("更新", "Updates");
        put("插件", "Plugins");
        put("配置备份", "Configuration backup");
        put("订阅", "Subscription");
        put("网络", "Network");
        put("文件", "Files");
        put("显示缩放", "Display scale");
        put("维护状态", "Maintenance status");
        put("运行日志", "Runtime log");
        put("文件浏览", "File browser");
        put("网络诊断", "Network diagnostics");
        put("Command Code 用量", "Command Code usage");

        put("保存", "Save");
        put("保存并重启", "Save & restart");
        put("保存中", "Saving");
        put("取消", "Cancel");
        put("关闭", "Close");
        put("确定", "OK");
        put("确认", "Confirm");
        put("继续", "Continue");
        put("退出", "Exit");
        put("重启", "Restart");
        put("刷新", "Refresh");
        put("刷新中…", "Refreshing…");
        put("重新检测", "Run again");
        put("正在检测…", "Checking…");
        put("正在读取…", "Loading…");
        put("正在打开…", "Opening…");
        put("导出", "Export");
        put("导出中…", "Exporting…");
        put("恢复", "Restore");
        put("安装", "Install");
        put("安装中…", "Installing…");
        put("启用", "Enable");
        put("已启用", "Enabled");
        put("删除", "Delete");
        put("分享", "Share");
        put("复制", "Copy");
        put("复制路径", "Copy path");
        put("重命名", "Rename");
        put("新建", "New");
        put("排序", "Sort");
        put("隐藏文件", "Hidden files");
        put("清空", "Clear");
        put("丢弃", "Discard");
        put("继续编辑", "Keep editing");
        put("覆盖保存", "Overwrite");
        put("再次输入", "Enter again");
        put("口令", "Password");
        put("输入", "Input");
        put("关闭提示", "Dismiss");
        put("新建会话", "New session");
        put("重试恢复", "Retry recovery");

        put("默认模型", "Default model");
        put("密钥仅保存在 App 私有目录，不会外传。",
                "Keys stay in the app's private storage and are never uploaded by the native shell.");
        put("当前 App 版本 ", "Current app version ");
        put("更新运行包（DSH / 工具链）", "Update runtime (DSH / toolchain)");
        put("检查 App 更新并安装", "Check and install app update");
        put("更新通道", "Update channel");
        put("稳定版", "Stable");
        put("测试版", "Test");
        put("查看运行日志", "View runtime log");
        put("导出诊断", "Export diagnostics");
        put("导出诊断包", "Export diagnostic bundle");
        put("管理插件", "Manage plugins");
        put("备份与恢复", "Backup & restore");
        put("浏览文件", "Browse files");
        put("启用内置插件，或从 npm 安装社区插件（重启后生效）",
                "Enable built-in plugins or install community plugins from npm. Changes apply after restart.");
        put("把账户密钥与模型配置导出到共享存储；重装或换机后可恢复",
                "Export encrypted account and model settings for reinstall or device migration.");
        put("查看 Command Code 账户余额、滚动窗口与本期用量",
                "View Command Code balance, rolling windows, and current usage.");
        put("检测更新功能依赖的各个源是否可用（直连与镜像分开报告）",
                "Check each update source and report direct and mirror connectivity separately.");
        put("浏览应用私有目录、工作区与共享存储；文本文件可直接编辑",
                "Browse app storage, workspace, and shared storage; edit text files directly.");
        put("界面布局已固定为桌面宽度，文字偏小可在此放大（立即生效）",
                "Increase text size without changing the desktop-width layout. Applies immediately.");
        put("支持 npm 包名、GitHub 简写（owner/repo）与绝对路径。",
                "Supports npm package names, GitHub owner/repo shortcuts, and absolute paths.");
        put("尚未安装第三方插件。", "No third-party plugins installed.");
        put("推荐", "Recommended");
        put("安装插件？", "Install plugin?");

        put("配置备份", "Configuration backup");
        put("备份包含账户密钥。新导出的 .dshbak 文件会使用",
                "The backup contains account keys. New .dshbak files use");
        put("恢复这份配置？", "Restore this configuration?");
        put("改动尚未保存", "Unsaved changes");
        put("文件已被外部修改", "File changed externally");
        put("直接关闭会丢失这次的改动。", "Closing now will discard your changes.");
        put("这是符号链接", "This is a symbolic link");
        put("结论：", "Result:");
        put("未配置 Command Code 密钥。\n", "Command Code key is not configured.\n");
        put("本期用量", "Current usage");
        put("用量不可用", "Usage unavailable");
        put("订阅信息不可用", "Subscription unavailable");
        put("额度", "Quota");
        put("额度不可用", "Quota unavailable");

        put("有任务正在运行", "A task is running");
        put("重启会中断当前正在执行的任务，确定要重启吗？",
                "Restarting will interrupt the running task. Restart anyway?");
        put("退出 DeepSeek Harness？", "Exit DeepSeek Harness?");
        put("退出后 agent 会在后台继续运行（通知栏可以看到状态），下次打开会立即回到当前界面。",
                "The agent will keep running in the background. Reopen the app to return here.");
        put("清空运行日志？", "Clear runtime log?");
        put("运行环境尚未就绪，请稍后再试", "The runtime is not ready yet. Try again shortly.");
        put("授权并启用插件？", "Authorize and enable plugin?");
        put("授权并启用", "Authorize & enable");
        put("用分享内容创建任务？", "Create a task from shared items?");
        put("创建任务", "Create task");
        put("分享内容已创建任务", "Task created from shared items");
        put("任务已填入，请确认后发送", "Task filled in. Review and send it.");
        put("文件已保存，但未找到任务输入框",
                "Files were saved, but the task editor was not found.");
        put("任务已排队，DSH 界面就绪后自动提交",
                "Task queued until the DSH interface is ready.");
        put("（暂无补丁记录）", "No patch status is available.");
        put("正在启动 DeepSeek Harness", "Starting DeepSeek Harness");
        put("正在准备运行环境…", "Preparing runtime…");
        put("正在重启服务…", "Restarting service…");
        put("已保存，正在重启服务…", "Saved. Restarting service…");
        put("显示缩放已设为 ", "Display scale set to ");
        put("选择文件", "Choose files");
    }

    private UiText() { }

    private static void put(String zh, String en) {
        ENGLISH.put(zh, en);
    }

    public static String normalize(String value) {
        if (value == null) return AUTO;
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (EN.equals(v) || v.startsWith("en-")) return EN;
        if (ZH.equals(v) || v.startsWith("zh-")) return ZH;
        return AUTO;
    }

    public static String resolve(String preference, String systemLanguage) {
        String p = normalize(preference);
        if (!AUTO.equals(p)) return p;
        String system = systemLanguage == null ? "" : systemLanguage.toLowerCase(Locale.ROOT);
        return system.startsWith("zh") ? ZH : EN;
    }

    public static void configure(String preference, String systemLanguage) {
        language = resolve(preference, systemLanguage);
    }

    public static boolean isEnglish() { return EN.equals(language); }

    public static String current() { return language; }

    public static String t(String zh, String en) {
        return isEnglish() ? en : zh;
    }

    /** Translate an exact native UI label; unknown and dynamic text is kept intact. */
    public static String text(String value) {
        if (value == null || !isEnglish()) return value;
        String exact = ENGLISH.get(value);
        if (exact != null) return exact;

        // A small set of safe prefix substitutions keeps dynamic status labels readable
        // without touching file names, model names, paths, or diagnostic output.
        String[][] prefixes = {
                {"当前 App 版本 ", "Current app version "},
                {"显示缩放已设为 ", "Display scale set to "},
                {"已保存 ", "Saved "},
                {"已删除 ", "Deleted "},
                {"已复制 ", "Copied "},
                {"检查失败：", "Check failed: "},
                {"保存失败: ", "Save failed: "},
                {"打开设置失败: ", "Could not open settings: "},
                {"安装失败：", "Install failed: "},
                {"导出失败：", "Export failed: "},
                {"恢复失败：", "Restore failed: "}
        };
        for (String[] pair : prefixes) {
            if (value.startsWith(pair[0])) return pair[1] + value.substring(pair[0].length());
        }
        return value;
    }

    /** Existing users must never be forced through the new first-run guide after upgrading. */
    public static boolean shouldShowFirstRun(boolean completed, boolean existingDshHome) {
        return !completed && !existingDshHome;
    }
}
