# 补丁 3：node-addon-system flock 支持 Android

## 问题
`@deepseek-ai/node-addon-system/lib/flock.js` 有两个 Android 不兼容点：

1. **平台白名单拒绝 android**
   ```js
   if (platform !== 'linux' && platform !== 'darwin') {
     throw ... code: 'ERR_FLOCK_UNSUPPORTED_PLATFORM'
   }
   ```
2. **原生绑定按 glibc/musl 加载**，且只发布 `-linux-` / `-darwin-` 平台包，
   没有 android 版本 → `require.resolve` 失败

它被 `dsh-session-persistence-jsonl` 用于 `tryLockExclusive()`（会话日志排他锁）。

## 改动
1. 平台白名单加入 `'android'`
2. 加载绑定失败时，降级为 **no-op 锁**（`tryLock: (_fd, done) => done(0)`）

## 为什么降级是可接受的
该锁用于**跨进程**协调同一会话日志的写入。手机上是单用户、单进程场景，
不依赖跨进程建议锁。真正的排他性由 `O_EXCL` 打开与补丁 2 的
"存在性检查 + rename" 提供。

## 应用方式
```bash
patch -p0 < flock.patch
```

## 全功能替代方案（如需）
Android 的 bionic 自 API 24 起提供 `flock(2)`。若要恢复真实文件锁，
可用 NDK 编译一个等价 `system.node` 并放入
`@deepseek-ai/node-addon-system-android-arm64/bin/`。
