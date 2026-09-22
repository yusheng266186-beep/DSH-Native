# 补丁 1：node-addon-require-builtin 纯 JS 垫片

## 问题
`node-addon-require-builtin` 只发布 darwin / linux / win32 的 **glibc** 原生绑定，
没有 android 构建。Android（bionic）上必然失败：

```
Error: dsh: host preparation failed:
       No usable native binding found for node-addon-require-builtin-android-arm64 (auto)
```

它被 `dsh-app-boot` 用来访问 Node 私有内部模块，为 profile 注入自定义模块解析
（`installProfileResolution()`，无条件调用）。

## 解法
**带 `--expose-internals` 启动 Node 时，可以绕过原生插件直接 `require` 内部模块。**

实测导出与原生插件所需完全一致：

| 内部模块 | 需要的导出 | 状态 |
|---|---|---|
| `internal/modules/cjs/loader` | `Module` | |
| `internal/modules/esm/loader` | `getOrInitializeCascadedLoader` | |
| `internal/modules/helpers` | `getCjsConditions` | |
| `internal/modules/esm/utils` | `getDefaultConditions` | |
| `internal/modules/esm/resolve` | `defaultResolve` | |

## 应用方式
```bash
# 1. 覆盖包的入口
cp node-addon-require-builtin.js \
   <dsh>/node_modules/node-addon-require-builtin/lib/index.js

# 2. 启动 Node 时必须加 --expose-internals
node --expose-internals <dsh>/lib/bin.js ...
```

## 验证
```
$ node --expose-internals -e 'require("internal/modules/esm/loader")'
5 个内部模块全部可取

$ node --expose-internals dsh/lib/bin.js --profile web --no-open --port 3099
dsh web: http://127.0.0.1:3099/?token=…
GET / → 200, 31252B, <title>DeepSeek Harness</title>
```
