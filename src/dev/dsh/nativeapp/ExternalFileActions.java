package dev.dsh.nativeapp;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import java.io.IOException;
import java.util.List;

/** User-initiated Android file intents. Sharing/viewing never grant write access. */
final class ExternalFileActions {
    private ExternalFileActions() { }

    static void launch(Activity activity, File file, String action, List<File> roots) throws IOException {
        if (!ShareTargets.isShareable(file, roots)) throw new IOException("该文件不能交给外部应用");
        boolean edit = "edit".equals(action), share = "share".equals(action);
        if (!edit && !share && !"open".equals(action)) throw new IOException("不支持的文件操作");
        Uri uri = Uri.parse("content://" + UpdateProvider.AUTHORITY + "/"
                + (edit ? ShareTargets.EDIT_PREFIX : ShareTargets.PREFIX) + ShareTargets.encode(file));
        Intent intent = new Intent(share ? Intent.ACTION_SEND : edit ? Intent.ACTION_EDIT : Intent.ACTION_VIEW);
        if (share) intent.setType(ShareTargets.mimeOf(file.getName())).putExtra(Intent.EXTRA_STREAM, uri);
        else intent.setDataAndType(uri, ShareTargets.mimeOf(file.getName()));
        intent.setClipData(ClipData.newRawUri(ShareTargets.displayName(file), uri));
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | (edit ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
        intent.addFlags(flags);
        activity.startActivity(Intent.createChooser(intent, UiText.t(share ? "分享文件" : edit ? "编辑文件" : "打开文件",
                share ? "Share file" : edit ? "Edit file" : "Open file")).addFlags(flags));
    }
}
