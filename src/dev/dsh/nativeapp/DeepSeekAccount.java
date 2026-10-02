package dev.dsh.nativeapp;

import java.net.URI;

/** Safe account-state decisions; the upstream core retains PKCE and credentials. */
final class DeepSeekAccount {
    private DeepSeekAccount() { }

    static boolean active(String phase) {
        return "initializing".equals(phase) || "waiting-browser".equals(phase)
                || "exchanging".equals(phase) || "committing".equals(phase);
    }

    static boolean canCancel(String phase) {
        return "initializing".equals(phase) || "waiting-browser".equals(phase)
                || "exchanging".equals(phase);
    }

    /** Production native UI opens only the exact upstream-owned HTTPS destinations. */
    static boolean browserUrl(String value, String path) {
        try {
            if (value == null || value.length() > 8192) return false;
            URI uri = new URI(value);
            return "https".equals(uri.getScheme()) && "platform.deepseek.com".equals(uri.getHost())
                    && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                    && path.equals(uri.getRawPath()) && uri.getFragment() == null;
        } catch (Exception invalid) { return false; }
    }

    static String status(String stored, String phase, String error, boolean en) {
        if ("initializing".equals(phase)) return en ? "Preparing browser authorization…" : "正在准备浏览器授权…";
        if ("waiting-browser".equals(phase)) return en ? "Finish authorization in your browser, then return here." : "请在浏览器中完成授权，再返回 App。";
        if ("exchanging".equals(phase) || "committing".equals(phase)) return en ? "Saving authorization…" : "正在保存授权…";
        if ("credential-stored".equals(stored)) return en ? "DeepSeek authorization saved" : "已保存 DeepSeek 账号授权";
        if ("expired".equals(phase)) return en ? "Authorization expired. Sign in again." : "授权已超时，请重新登录。";
        if ("failed".equals(phase)) {
            if ("network".equals(error)) return en ? "Could not reach DeepSeek. Check your connection and retry." : "无法连接 DeepSeek，请检查网络后重试。";
            if ("storage".equals(error)) return en ? "Could not save authorization. Check free storage and retry." : "无法保存授权，请检查剩余空间后重试。";
            return en ? "Authorization failed. Please retry." : "授权失败，请重试。";
        }
        if ("cancelled".equals(phase)) return en ? "Authorization cancelled" : "授权已取消";
        return en ? "Not signed in to DeepSeek" : "尚未登录 DeepSeek 账号";
    }
}
