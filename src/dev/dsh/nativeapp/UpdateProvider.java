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

    /**
     * 允许通过本 Provider 分享出去的根。
     *
     * <p>与应用内文件浏览的可写范围一致：只含应用自己的目录与
     * {@code /sdcard/DSHNative}。刻意不含 {@code /} 与整个 {@code /sdcard} ——
     * 那是把系统文件与其它应用的数据递出去。
     */
    private java.util.List<File> allowedRoots() {
        java.util.List<File> out = new java.util.ArrayList<File>();
        android.content.Context ctx = getContext();
        if (ctx != null) {
            // apkFile 位于 <root>/cache/update.apk，私有根即 cache 的父目录
            File cache = ctx.getCacheDir();
            if (cache != null && cache.getParentFile() != null) out.add(cache.getParentFile());
        }
        File shared = new File("/sdcard/DSHNative");
        if (shared.isDirectory()) out.add(shared);
        return out;
    }

    /**
     * 从 URI 解出要分享的文件。
     *
     * <p>用 {@code getEncodedPath()} 而不是 {@code getPath()}：后者会把
     * {@code %2F} 解码成 {@code /}，路径里的分隔符就丢了。
     * 这里只解码一次，交给 {@link ShareTargets#decode}。
     *
     * @return 文件；不是分享 URI、格式不合法或不在白名单内时返回 null
     */
    private File shareTarget(Uri uri, boolean enforceWhitelist) {
        String encoded = uri == null ? null : uri.getEncodedPath();
        if (encoded == null) return null;
        String head = "/" + ShareTargets.PREFIX;
        if (!encoded.startsWith(head)) return null;
        File target = ShareTargets.decode(encoded.substring(head.length()));
        if (target == null) return null;
        if (enforceWhitelist && !ShareTargets.isShareable(target, allowedRoots())) return null;
        return target;
    }

    /** 更新包在缓存目录中的固定文件名。 */
    public static final String APK_NAME = "update.apk";

    /** 更新包以 content URI 形式交给系统安装器。 */
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

        // 分享路径：content://<authority>/f/<转义后的绝对路径>
        //
        // 解码成功不代表可以分享：URI 是**外部应用**传进来的，
        // 对方能自己构造路径来读任意文件 —— 白名单必须独立再校验一遍。
        if (uri != null && uri.getEncodedPath() != null
                && uri.getEncodedPath().startsWith("/" + ShareTargets.PREFIX)) {
            File target = shareTarget(uri, true);
            if (target == null) throw new FileNotFoundException("not shareable: " + uri);
            return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY);
        }

        File f = apkFile(getContext());
        if (!f.exists()) throw new FileNotFoundException(f.getAbsolutePath());
        // 更新包只读：安装器只需读取
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        File target = shareTarget(uri, false);
        if (target != null) return ShareTargets.mimeOf(target.getName());
        return "application/vnd.android.package-archive";
    }

    /**
     * 供接收方查询文件名与大小。
     *
     * <p>不少应用（尤其是文件管理器与聊天工具）会先 query 这两个字段来显示
     * 附件名 —— 不实现的话对方只能显示一串转义后的路径。
     * 更新包那条路径不需要（安装器只读描述符），返回 null。
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File target = shareTarget(uri, true);
        if (target == null) return null;
        android.database.MatrixCursor c = new android.database.MatrixCursor(new String[]{
                android.provider.OpenableColumns.DISPLAY_NAME,
                android.provider.OpenableColumns.SIZE});
        c.addRow(new Object[]{ ShareTargets.displayName(target), target.length() });
        return c;
    }

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
