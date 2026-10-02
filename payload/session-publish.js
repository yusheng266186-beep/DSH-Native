/* DSH-ANDROID-SESSION-PUBLISH-v1 */
// Preserve create-only publication when Android or a filesystem rejects links.
// An exclusive copy preserves collision safety, but is not an atomic hard link.
async function __dshPublishLink(source, target, linkFile, copy = androidCopyFile) {
    try {
        await linkFile(source, target);
    } catch (error) {
        if (!['EACCES', 'EPERM', 'EXDEV', 'ENOSYS', 'EOPNOTSUPP', 'ENOTSUP', 'EMLINK'].includes(error?.code)) throw error;
        await copy(source, target, androidFsConstants.COPYFILE_EXCL);
        const handle = await open(target, 'r+');
        try { await handle.sync(); } finally { await handle.close(); }
    }
}
/* DSH-ANDROID-SESSION-PUBLISH-END */
