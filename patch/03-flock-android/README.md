# Android flock 兼容边界

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**0.33.8**；源码：**0.33.9**；源码运行包：`payload-v12`；固定 DSH：`0.2.0-rc.2`（上游候选版）。[统一进度与验证边界](../../docs/STATUS.md)。
<!-- dsh-doc-status:end -->

## 当前实现

payload-v10 的运行包构建仅在 Android 平台使用无法加载原生 flock 时的降级入口。Linux/桌面仍加载真实绑定，并保留锁竞争与加载错误的传播，不能在所有平台失败时统一返回“成功”。

原实现存在“任何平台原生加载失败都静默取消锁”的问题，当前 `prepare_core_payload.py` 已按平台收窄。旧补丁文件属于历史参考，当前构建逻辑是现行依据。

## 降级不等于互斥

Android no-op 降级**不提供跨进程 advisory lock**。App 受控启动与进程管理减少多实例风险，但不能把这些条件写成文件锁已经生效，更不能靠“存在性检查 + rename”证明并发安全。

会话文件发布另有硬链接优先、`COPYFILE_EXCL` 拒绝覆盖的保护，见 [会话发布兼容说明](../02-session-link-to-rename/README.md)。锁与文件独占发布是不同机制。

## 验证与后续修改

真实运行包回归测试 Linux 的真实锁竞争，以及模拟 Android 平台入口；它不等同于手机内核上执行原生 flock。

未来若引入 Android/bionic 原生锁实现，需要核对 ABI、错误传播、重复启动与进程退出释放行为。不得通过抑制所有异常使测试变绿。详细运行边界见 [ARCHITECTURE](../../docs/ARCHITECTURE.md)，证据见 [STATUS](../../docs/STATUS.md)。
