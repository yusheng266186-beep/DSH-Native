# DSH Native payload v10

<!-- dsh-doc-status:start -->
> 版本记录：标题版本对应本次发布，原始变更内容保留，不覆盖为新版说明。 已发布 stable：**0.33.9**；源码：**0.33.10**；源码运行包：`payload-v12`；固定 DSH：`0.2.0-rc.2`（上游候选版）。[统一进度与验证边界](../docs/STATUS.md)。
<!-- dsh-doc-status:end -->

- Official DSH CLI and dsh-* modules: 0.2.0-rc.2 (upstream release candidate).
- npm dependency lock and CLI SHA-512 pinned in runtime/core-*.json.
- Preserve verified Android PTY and Node-internals shim from payload-v9.
- Restrict flock fallback to Android; retain native contention on desktop.
- Preserve exclusive session publication with COPYFILE_EXCL fallback for unsupported hardlinks.
- DSH shard revision 5; remove obsolete shipped files and old attachment backup.
- Actual unpacked allocation size included for disk-space preflight.
- All four tools shards are byte-identical to payload-v9.
- App unpack.js, 92 model efforts, offline SDK bodies, vision, session persistence, locks, and full Web boot verified.
