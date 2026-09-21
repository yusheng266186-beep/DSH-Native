# 补丁 2：session-persistence 硬链接改为 rename

## 问题
`dsh-session-persistence-jsonl` 用 `link(2)` 做原子发布（先写临时文件，再硬链接到目标，
借 `EEXIST` 保证"仅创建不覆盖"）。**bionic 拒绝硬链接，返回 `EACCES`**，
导致会话日志无法落盘。

## 改动（两处）
1. `materializePosix()` — `link(tmp, finalPath)` → `rename(tmp, finalPath)`
2. `publishCurrentExclusive()` — `link(staged, currentPath)` →
   先 `lstat` 判断存在性（存在则返回 false，保持"仅创建"语义），再 `rename`

`rename(2)` 在同目录下同样是原子的，因此不损失原子性。

## 应用方式
```bash
patch -p0 < session-persistence-jsonl.patch
# 或用备份对比：先备份 lib/index.js，再手工套用
```

## 影响
- 牺牲了 `link` 那种"原子性 + 排他性"的合并保证，改为"检查后再改名"。
  在单用户、单进程的手机场景下不构成实际问题。
- 上游若把发布方式改为 `rename`，本补丁即可废弃
  （社区已在 DSH Discussion #1588 中向官方提出该建议）。
