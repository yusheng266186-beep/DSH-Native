# 发版检查清单

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**0.33.5**；源码：**0.33.6**；源码运行包：`payload-v12`；固定 DSH：`0.2.0-rc.2`（上游候选版）。[统一进度与验证边界](../docs/STATUS.md)。
<!-- dsh-doc-status:end -->

每轮用户修改按现行约定递增并发布 stable，先完成 PR 的完整 Android CI，再合并 main，然后只用 `.github/workflows/release.yml` / `scripts/release.sh`。历史阶段的候选边界不覆盖这个交付约定。

## 发版前

- [ ] 目标变更完整可审阅，版本递增，已有用户数据与运行安全边界保持。
- [ ] 本版 `release-notes/vX.Y.Z.md` 描述真实变更、检查与验证限制。
- [ ] 中英文 README 与相关现行文档更新；运行 docs-only 同步所有状态块与表。
- [ ] 本地逻辑/脚本回归和元数据/文档检查通过；完整 Android CI 成功。
- [ ] 保持包名 `dev.dsh.native` 与既有签名，不能生成新密钥。
- [ ] 若更新 payload，先验证并完整发布 payload，App 仅引用可下载标签。
- [ ] 对真机与真实服务商验证范围如实记录，不能将编译通过扩大为全设备验收。

## 工作流输入

| 输入 | stable 正式版 | test 专项预发布 |
|---|---|---|
| `version` | 递增 X.Y.Z | 独立递增 X.Y.Z |
| `channel` | stable | test |
| 标签 | `vX.Y.Z-bootstrap` | `vX.Y.Z-test` |
| GitHub 状态 | 非草稿正式发布 | 非草稿 Prerelease |
| 清单 | `latest.json` | `latest-test.json` |
| README 下载入口 | 更新为本次 stable | 继续保留 stable |

`dry_run=true` 可选用于先演练构建和权限；不上传、不写正式清单。`signer_ref` 可明确指定已知正确 bootstrap 版本，默认筛选上一正式 APK。

## 必须按顺序执行的保护

1. 核对既有签名与已发布 APK，同步版本。
2. 完整构建与真实运行包消费者回归。
3. 上传 Release 资产并核对大小/摘要、通道与非草稿状态。
4. 验证直连和镜像下载路径。
5. 最后写相应 latest 清单，按真实 APK / payload 计算字节与 SHA-256。
6. 同步双 README、所有文档状态、STATUS 表和模型快照表，严格校验后提交 main。

## 发版后

- [ ] Release APK 存在，tag/包名/版本/签名与清单一致。
- [ ] stable/test 清单保持各自 APK 对应的 payload，旧测试版不能改成新版 payload。
- [ ] main 已含最终发布元数据，`sync_project_metadata.py --check` 通过。
- [ ] README 下载、体积、摘要与全部文档状态一致。
- [ ] 真机覆盖安装与具体功能反馈另记日期、版本和设备；不执行卸载或清数据。

若发布失败，保留原已发布清单并定位失败步骤，禁止手写清单宣布不存在的产物。更多说明见 [BUILD](../docs/BUILD.md)。
