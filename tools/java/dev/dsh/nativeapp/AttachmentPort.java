package dev.dsh.nativeapp;

/** Build-time attachment durability adapter; never rewrites a phone runtime. */
final class AttachmentPort {
    static String apply(String src) {
        final String tag = "/* DSH-ANDROID-ATTACH-PATCH-v3 */";
        if (src != null && src.contains(tag)) return src;
        if (src == null || src.indexOf("await syncDirectory(") < 0) return null;
        // 必须先替换调用点、再前置包装函数 —— 反过来会把包装函数自身的调用
        // 也替换掉，造成自我递归（实测 RangeError: Maximum call stack size exceeded）。
        String out = src.replace("await syncDirectory(", "await __androidSyncDirectory(");
        // DSH 的构建格式在版本间会切换是否保留分号；两种写法都要覆盖，
        // 否则内核升级后目录持久化补丁会生效，但附件硬链接回退会静默失效。
        out = out.replace("await link(staged.path, target);", "await __androidLink(staged.path, target);");
        out = out.replace("await link(staged.path, target)", "await __androidLink(staged.path, target)");
        out = out.replace("await link(source, target);", "await __androidLink(source, target);");
        out = out.replace("await link(source, target)", "await __androidLink(source, target)");

        String marker = "throw new AttachmentError(\"Unable to persist attachment.\", "
                + "\"ATTACHMENT_WRITE_FAILED\", { cause: error });";
        String singleMarker = "throw new AttachmentError('Unable to persist attachment.', "
                + "'ATTACHMENT_WRITE_FAILED', { cause: error })";
        String detail = "console.error('[dsh-attach] persist failed: ' + String(error && error.code)"
              + " + ' ' + String(error && error.message)"
              + " + (error && error.cause ? (' <= ' + String(error.cause.code) + ' '"
              + " + String(error.cause.message)) : ''));\n\t\t\t"
              + "throw new AttachmentError('Unable to persist attachment. ['"
              + " + String(error && error.code) + '] ' + String(error && error.message)"
              + " + (error && error.cause ? (' <= ' + String(error.cause.code) + ' '"
              + " + String(error.cause.message)) : ''), 'ATTACHMENT_WRITE_FAILED', { cause: error })";
        if (out.contains(marker)) {
            out = out.replace(marker, detail + ";");
        } else if (out.contains(singleMarker)) {
            out = out.replace(singleMarker, detail);
        }

        String helper = tag + "\n"
              + "async function __androidSyncDirectory(p){try{return await syncDirectory(p);}"
              + "catch(e){console.error('[dsh-attach] syncDirectory skipped ' + p + ': '"
              + " + String(e && e.code));}}\n"
              + "async function __androidLink(from,to){try{return await link(from,to);}"
              + "catch(e){var c=e&&e.code;"
              + "if(c==='EPERM'||c==='EXDEV'||c==='ENOSYS'||c==='EACCES'||c==='EMLINK'"
              + "||c==='EOPNOTSUPP'){"
              + "console.error('[dsh-attach] link unsupported (' + c + '), copy instead');"
              + "const b=await readFile(from);const fs=await import('node:fs/promises');"
              + "await fs.writeFile(to,b,{mode:384});return;}"
              + "throw e;}}\n";
        out = helper + out;

        // 自检：包装函数体内必须仍是原始调用，否则会自我递归
        if (out.indexOf("__androidSyncDirectory(p){try{return await syncDirectory(p);}") < 0
                || out.indexOf("__androidLink(from,to){try{return await link(from,to);}") < 0) {
            return null;
        }
        return out;
    }
}
