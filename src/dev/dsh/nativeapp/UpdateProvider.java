package dev.dsh.nativeapp;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 把下载好的更新包以 {@code content://} 形式提供给系统安装器。
 *
 * <p>为什么不直接传文件路径：Android 7.0（API 24）起，
 * 把 {@code file://} URI 交给其他应用会抛 {@code FileUriExposedException}，
 * 官方要求改用 content URI。常规做法是 androidx 的 FileProvider，
 * 但本项目不依赖 androidx，因此这里手写一个最小实现 ——
 * 只需要 {@link #openFile} 能返回文件描述符即可。
 *
 * <p>provider 声明为 {@code exported=false} 且 {@code grantUriPermissions=true}，
 * 安装器通过一次性授权读取，不对外暴露。
 */
public class UpdateProvider extends ContentProvider {

    /** 与清单里 android:authorities 保持一致。 */
    public static final String AUTHORITY = "dev.dsh.native.updates";

    /** 更新包在缓存目录中的固定文件名。 */
    public static final String APK_NAME = "update.apk";

    public static Uri contentUri() {
        return Uri.parse("content://" + AUTHORITY + "/" + APK_NAME);
    }

    public static File apkFile(android.content.Context ctx) {
        return new File(ctx.getCacheDir(), APK_NAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (getContext() == null) throw new FileNotFoundException("no context");
        File f = apkFile(getContext());
        if (!f.exists()) throw new FileNotFoundException(f.getAbsolutePath());
        // 只读打开：安装器只需读取
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    // 本 provider 只读，其余接口无需实现
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
