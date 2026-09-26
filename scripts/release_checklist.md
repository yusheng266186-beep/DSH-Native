# 发版流程

APK 必须由 `.github/workflows/release.yml` 构建和发布。不要手工创建 Release，
不要手工编辑 `latest.json` 或 `latest-test.json`，也不要把 APK 提交进仓库。

## 通道

| 通道 | Release 标签 | GitHub 状态 | 更新清单 | README |
|---|---|---|---|---|
| `test` | `vX.Y.Z-test` | Prerelease | `latest-test.json` | 不改 |
| `stable` | `vX.Y.Z-bootstrap` | 正式发布 | `latest.json` | 同步版本、体积与 SHA-256 |

稳定通道客户端只读取 `latest.json`。测试通道客户端同时读取两个清单并采用更高版本，
因此测试用户不会错过后来发布的更高稳定版。

## 步骤

1. 合并目标代码，确认主分支构建为绿灯。
2. 准备 `release-notes/vX.Y.Z.md`。
3. 手动运行“发版”工作流，先设置 `dry_run=true`：
   - `version=X.Y.Z`
   - `channel=test` 或 `stable`
   - `signer_ref` 指向一个确认可覆盖安装的已发布版本
4. 演练成功后以相同输入运行 `dry_run=false`。
5. 工作流会依次完成：签名比对、升版本、完整构建、创建 Release、验证资产、
   轮询直连与镜像下载、更新对应清单、把版本与清单提交回 `main`。
6. 检查 Release 资产存在、清单的版本/标签/SHA-256 正确，并在真机覆盖安装。

## 不能跳过的保护

- **签名一致性**：换密钥会使现有用户无法覆盖安装并被迫丢失私有数据。
- **先发布后写清单**：清单一旦指向不存在或不可下载的 APK，所有客户端更新都会失败。
- **版本号递增**：Android 使用 `versionCode` 判断升级；`bump_version.sh` 从语义版本自动生成。
- **测试先于稳定**：尚未真机验收的版本只发 `test`，避免影响稳定用户。

运行包 payload 有变化时，应先完成 payload Release，并最后上传它的 `manifest.json`；
App Release 只能引用已经完整可下载的 payload 标签。
