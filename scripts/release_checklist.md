# 发版流程

App 内自更新依赖仓库根目录的 `latest.json`，**每次发版必须同步更新它**，
否则 App 检查更新时会认为已是最新。

## 步骤

1. **改版本号**（两处，保持一致）
   - `mkmanifest.py` 里的 `s("x.y.z")` —— versionName
   - `MainActivity.java` 里的 `APK 版本: x.y.z`（仅日志用）
   - `versionCode` 由 `mkmanifest.py` 自动按 `major*10000+minor*100+patch` 推导

2. **构建**
   ```bash
   bash build_bootstrap.sh
   ```
   产出 `bootstrap/DSHNative-bootstrap.apk`

3. **发布 APK**
   ```bash
   gh release create vX.Y.Z-bootstrap --repo yusheng266186-beep/DSH-Native \
     --title "..." --notes "..." ./DSHNative-bootstrap.apk
   ```
   ⚠️ tag 必须遵循 `vX.Y.Z-bootstrap` 格式

4. **更新 `latest.json`**（关键，容易漏）
   ```json
   {
     "version": "X.Y.Z",
     "tag": "vX.Y.Z-bootstrap",
     "apk": "DSHNative-bootstrap.apk",
     "payload": "payload-vN"
   }
   ```
   提交并推送 —— 这一步之后 App 才能检测到新版本。

5. **若运行包有变化**：先发 payload release，更新 `manifest.json` 与
   `MainActivity` 里的 `ASSET_PATH`。

## UI 设计规范（每次发版必查）

**所有原生界面必须使用 `DshUi` 组件层**，禁止系统默认样式 ——
详见 [docs/DESIGN.md](../docs/DESIGN.md)。

构建脚本已内置强制检查：出现 `AlertDialog` 会直接**构建失败**。
新增原生界面时请对照该文档第三节的检查清单。

## 为什么要用静态 latest.json 而不是 GitHub API

GitHub API 未认证请求限 **60 次/小时且按 IP 计**。
手机流量多为运营商 NAT 共享 IP，实测直接返回 `403 rate limit exceeded`。
静态文件走 CDN，没有这个限制。

## 为什么 versionCode 必须递增

系统据此判断新旧。恒为 1 会导致更新语义混乱。
