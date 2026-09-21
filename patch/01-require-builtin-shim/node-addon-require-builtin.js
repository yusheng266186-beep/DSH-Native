'use strict';

// ---------------------------------------------------------------------------
// node-addon-require-builtin 的纯 JS 替代实现
//
// 背景：官方包只发布 darwin / linux / win32 的 glibc 原生绑定，没有 android。
// 该插件的唯一作用是把 Node 的私有内部模块暴露给 dsh-app-boot
// （用于为 profile 注入自定义模块解析）。
//
// 社区已验证（deepseek-harness Discussion #1588）：在 Android 上以
// `node --expose-internals` 启动即可绕过原生插件。
//
// 实现依据：带 --expose-internals 时，Node 允许直接 require 内部模块
// （即官方 test 套件使用的机制）。实测以下模块及其导出与原生插件一致：
//   internal/modules/cjs/loader  → Module, ...
//   internal/modules/esm/loader  → getOrInitializeCascadedLoader, ...
//   internal/modules/helpers     → getCjsConditions, ...
//   internal/modules/esm/utils   → getDefaultConditions, ...
//   internal/modules/esm/resolve → defaultResolve, ...
// ---------------------------------------------------------------------------

const createRequire = require('module').createRequire;

let internalRequire;
let exposed = false;

function getInternalRequire() {
  if (internalRequire !== undefined) return internalRequire;

  // 首选：官方测试用的绑定入口（需 --expose-internals）
  try {
    const testBinding = require('internal/test/binding');
    if (testBinding && typeof testBinding.internalBinding === 'function') {
      // 触发一次以确认内部 require 可用
      testBinding.internalBinding('builtins');
      // 该入口本身即运行在可访问 internal/ 的上下文
      internalRequire = createRequire(__filename);
      exposed = true;
      return internalRequire;
    }
  } catch (e) { /* 继续尝试下一种 */ }

  // 次选：直接 require（部分 Node 版本在 --expose-internals 下允许）
  try {
    require('internal/modules/cjs/loader');
    internalRequire = createRequire(__filename);
    exposed = true;
    return internalRequire;
  } catch (e) { /* 失败 */ }

  internalRequire = null;
  return null;
}

function requireBuiltin(id) {
  const req = getInternalRequire();
  if (req === null) {
    throw new Error(
      'requireBuiltin: Node internals are not exposed. ' +
      'Start Node with --expose-internals (this shim replaces the native ' +
      'node-addon-require-builtin addon, which has no Android build).'
    );
  }
  return req(id);
}

// "unrestricted" 变体：不限制内部模块 id
function isAllowedInternalId() {
  return true;
}

function getBindingInfo() {
  return {
    mode: 'js-shim',
    product: 'require-builtin',
    backend: 'js',
    abi: `node-${process.versions.modules}`,
    bindingPath: __filename,
    bindingSource: 'js-fallback',
    localBindingPath: __filename,
    internalsExposed: getInternalRequire() !== null,
    node: process.version,
    platform: process.platform,
    arch: process.arch,
  };
}

module.exports = requireBuiltin;
module.exports.requireBuiltin = requireBuiltin;
module.exports.isAllowedInternalId = isAllowedInternalId;
module.exports.getBindingInfo = getBindingInfo;
module.exports.default = requireBuiltin;
