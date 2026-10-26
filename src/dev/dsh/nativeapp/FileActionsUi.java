package dev.dsh.nativeapp;

/** Add file identity to the original delivery slots, preserving their authorized preview actions. */
final class FileActionsUi {
    private FileActionsUi() { }
    static String patch(String source) {
        String marker = "/* dsh-native-delivery-file-actions-v1 */";
        if (source == null) return null;
        if (source.contains(marker)) return source;
        String out = once(source, "actionUrl: presentedFileUrl(sessionId, file.seq, file.index),",
                "actionUrl: presentedFileUrl(sessionId, file.seq, file.index), absolutePath: file.path.startsWith('/') ? file.path : (cwd ? cwd + '/' + file.path : void 0),");
        out = once(out, "actionUrl: changedFileUrl(sessionId, seq, index),",
                "actionUrl: changedFileUrl(sessionId, seq, index), absolutePath: file.path.startsWith('/') ? file.path : (cwd ? cwd + '/' + file.path : void 0),");
        return out == null ? null : marker + "\n" + out;
    }
    static String preview(String source) {
        if(source==null)return null;
        if(source.contains("data-native-file-toolbar"))return source;
        String anchor="className: TextPreview_module_css_default.header,";
        int count=source.split(java.util.regex.Pattern.quote(anchor),-1).length-1;
        if(count!=2)return null;
        return source.replace(anchor,anchor+" \"data-native-file-toolbar\": true,");
    }
    private static String once(String source,String before,String after) {
        if(source==null)return null;int at=source.indexOf(before);
        if(at<0||source.indexOf(before,at+before.length())>=0)return null;
        return source.substring(0,at)+after+source.substring(at+before.length());
    }
}
