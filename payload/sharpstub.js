// Android patch: sharp 只提供 glibc 链接的预编译产物，在 Android(bionic) 上
// 无法 dlopen。它被 dsh-attachment-local 惰性加载（仅处理图片时），
// 这里提供优雅降级的桩，给出可读错误而非晦涩的加载器报错。
// 文字对话、代码编辑、文件与终端工具均不受影响。

function unavailable() {
  const err = new Error(
    '图片处理在当前平台不可用：sharp 没有 Android(bionic) 构建。' +
    '文字对话、代码编辑与文件操作不受影响。');
  err.code = 'ERR_SHARP_UNAVAILABLE_ANDROID';
  throw err;
}

module.exports = unavailable;
module.exports.versions = { stub: true, platform: 'android' };
module.exports.format = {};
module.exports.cache = function () { return module.exports; };
module.exports.concurrency = function () { return module.exports; };
module.exports.simd = function () { return false; };
