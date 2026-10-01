# Node 内部模块的 Android 兼容垫片

<!-- dsh-doc-status:start -->
> 现行文档：按当前源码维护。 已发布 stable：**0.33.0**；源码：**0.33.0**；源码运行包：`payload-v10`；固定 DSH：`0.2.0-rc.2`（上游候选版）。[统一进度与验证边界](../../docs/STATUS.md)。
<!-- dsh-doc-status:end -->

## 当前定位

DSH 的 profile 准备需要访问 Node 内部模块。原生 `node-addon-require-builtin` 没有可直接用于当前 Android/bionic 运行时的对应绑定；本项目保留纯 JS 垫片，并以 `--expose-internals` 启动 Node。

现行 `prepare_core_payload.py` 从已验证的基础运行包保留此兼容入口。它是运行包构建的一部分，用户不需要手工复制补丁。

## 接口与启动要求

垫片访问 CJS/ESM 加载、条件和解析模块，例如 `internal/modules/cjs/loader`、`internal/modules/esm/loader`、`internal/modules/helpers`、`internal/modules/esm/utils` 与 `internal/modules/esm/resolve`。

```bash
node --expose-internals /path/to/dsh/lib/bin.js --patch /path/to/app.patch.yml \
  --profile web --no-open --port 3099
```

示例路径与端口用于说明参数顺序；实际 App 启动参数由原生层生成。`--patch` 必须在 `--profile` 前。没有 expose-internals，单独复制 JS 文件不能保证内部模块可访问。

## 升级与验证

升级 Node 或 DSH 时必须核对垫片所需导出与新的 profile 准备逻辑，不假定旧私有 API 永远稳定，也不把 glibc binding 复制到 Android。

`check_model_consumer.sh` 消费真实运行包，使用该启动模式验证 Host、模型配置和无凭据 Web profile 的 token/Cookie/页面响应。Linux 容器验证不能证明手机上每个 ELF 的加载行为，真机范围见 [STATUS](../../docs/STATUS.md)。
