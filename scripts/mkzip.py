import os, sys, zipfile

src, out = sys.argv[1], sys.argv[2]
files = []
for root, dirs, names in os.walk(src):
    for n in names:
        full = os.path.join(root, n)
        rel = os.path.relpath(full, src).replace(os.sep, '/')
        files.append((full, rel))
files.sort(key=lambda x: x[1])

# 策略：assets/ 下的内容一律压缩（运行时由 AssetManager 解压，无需 zip 内对齐）。
# 仅对真正的 APK 级原生库（lib/<abi>/*.so）才需要 STORED + 对齐，本 PoC 不使用该路径。
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for full, rel in files:
        zi = zipfile.ZipInfo(rel)
        zi.compress_type = zipfile.ZIP_DEFLATED
        zi.external_attr = 0o644 << 16
        with open(full, 'rb') as f:
            z.writestr(zi, f.read())

size = os.path.getsize(out)
# 校验：读回所有条目，确认可解压且内容一致
bad = 0
with zipfile.ZipFile(out) as z:
    if z.testzip() is not None: bad += 1
    n = len(z.namelist())
print(f"  条目数: {n}   完整性: {'通过' if bad == 0 else '失败'}")
print(f"  输出: {out}   {size/1024/1024:.1f} MB")
