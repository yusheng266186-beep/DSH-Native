#!/usr/bin/env python3
"""把运行包切分为多个分片，并生成 manifest.json。

为什么要切分
------------
之前只有两个大包（dsh 34MB + tools 31MB），任何一处变动都要重下整个包。
例如只是给工具链加个 npm，也得重下 31MB。

切分后按分片校验，只有**真正变化的分片**才会重新下载。

免下载迁移
----------
manifest 里为每个分片带一个"哨兵文件"（路径 + 大小 + sha256）。
App 启动时先比对哨兵：若本地已解压内容与 manifest 一致，
即使没有下载记录也直接判定为"已就绪"，无需重新下载。
这样本次从旧结构迁移到分片结构时，用户不需要重下任何东西。
"""

import hashlib
import json
import os
import subprocess
import sys

PKG = '/root/build/pkg'
TOOLS = os.environ.get('DSH_TOOLS_DIR') or os.path.join(PKG, 'tools_v3')
OUT = os.environ.get('DSH_PAYLOAD_OUT') or os.path.join(PKG, 'payload-v4')

# 分片定义：(归档名, 目标目录, 哨兵相对路径)
# 哨兵必须选「会随该分片内容变化」的文件：App 靠比对哨兵判断分片是否需要更新。
# 若哨兵恰好在本次变更中未改动，App 会误判为已就绪、跳过下载。
PARTS = [
    ('dsh.tar.zst',          'dsh',   'lib/bin.js'),
    ('tools-base.tar.zst',   'tools', 'share/git-core/templates/description'),
    ('tools-libs.tar.zst',   'tools', 'lib/libwebp.so'),          # 本次新增该库
    ('tools-python.tar.zst', 'tools', 'lib/python3.14/site-packages/pypdf/__init__.py'),  # 本次新增 pypdf
    ('tools-npm.tar.zst',    'tools', 'lib/node_modules/npm/package.json'),
]


def sha256_file(path, limit=None):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        while True:
            b = f.read(1 << 20)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def classify(rel):
    """决定文件属于哪个分片。每个文件只归一个分片，避免重复下载。"""
    if rel.startswith('lib/python3.14') or rel.startswith('bin/python') \
            or rel.startswith('bin/pip'):
        return 'tools-python.tar.zst'
    if rel.startswith('lib/node_modules') or rel in ('bin/npm', 'bin/npx'):
        return 'tools-npm.tar.zst'
    if rel.startswith('lib/') and '.so' in rel:
        return 'tools-libs.tar.zst'
    return 'tools-base.tar.zst'


def main():
    os.makedirs(OUT, exist_ok=True)

    # ---------- 1. 分类 ----------
    groups = {name: [] for name, _, _ in PARTS}
    for root, _dirs, files in os.walk(TOOLS):
        for f in files:
            full = os.path.join(root, f)
            rel = os.path.relpath(full, TOOLS)
            groups[classify(rel)].append(rel)

    for name in groups:
        if name != 'dsh.tar.zst':
            print(f"  {name:<22} {len(groups[name]):>5} 个文件")

    # ---------- 2. 打包（dsh 已存在，直接复用）----------
    for name, _target, _sent in PARTS:
        out = os.path.join(OUT, name)
        if name == 'dsh.tar.zst':
            src = os.path.join(PKG, 'dsh.tar.zst')
            if not os.path.exists(src):
                sys.exit(f"缺少 {src}")
            print(f"  复用 {name}")
            subprocess.run(['cp', src, out], check=True)
            continue
        listing = groups[name]
        if not listing:
            sys.exit(f"分片 {name} 为空，分类逻辑可能出错")
        # zstd 默认不覆盖已存在文件，先删掉
        if os.path.exists(out):
            os.remove(out)
        print(f"  打包 {name} … ({len(listing)} 个文件)")
        tar = subprocess.Popen(['tar', '-cf', '-', '-C', TOOLS, '-T', '-'],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE)
        zst = subprocess.Popen(['zstd', '-10', '-T8', '-q', '-o', out],
                               stdin=tar.stdout)
        tar.stdout.close()
        tar.stdin.write(('\n'.join(listing) + '\n').encode())
        tar.stdin.close()
        tar.wait()
        zst.wait()
        if tar.returncode or zst.returncode:
            sys.exit(f"打包 {name} 失败")

    # ---------- 3. manifest ----------
    parts = []
    for name, target, sentinel in PARTS:
        archive = os.path.join(OUT, name)
        size = os.path.getsize(archive)
        digest = sha256_file(archive)

        # 哨兵：从解压后的实际内容计算
        if target == 'tools':
            sent_path = os.path.join(TOOLS, sentinel)
        else:
            # dsh 分片：从归档里取
            # 注意：zstd -dc 已解出纯 tar，这里不能再带 -z，
            # 否则 tar 会试图把纯 tar 当 gzip 解压而失败（曾把哨兵算成 0 字节）。
            data = subprocess.run(
                ['tar', '-xf', '-', '-O', './' + sentinel],
                input=subprocess.run(['zstd', '-dc', archive],
                                     capture_output=True).stdout,
                capture_output=True).stdout
            if not data:
                sys.exit(f"哨兵提取失败: {sentinel}")
            sent_sha = hashlib.sha256(data).hexdigest()
            sent_size = len(data)
            parts.append({
                'name': name, 'target': target, 'size': size, 'sha256': digest,
                'sentinel': {'path': sentinel, 'size': sent_size, 'sha256': sent_sha},
            })
            print(f"  {name:<22} {size/1048576:6.1f}MB  哨兵 {sentinel} ({sent_size}B)")
            continue

        sent_sha = sha256_file(sent_path)
        sent_size = os.path.getsize(sent_path)
        parts.append({
            'name': name, 'target': target, 'size': size, 'sha256': digest,
            'sentinel': {'path': sentinel, 'size': sent_size, 'sha256': sent_sha},
        })
        print(f"  {name:<22} {size/1048576:6.1f}MB  哨兵 {sentinel} ({sent_size}B)")

    # revision：**内容修订号**，任何实质性变化（包括只删文件）都要递增。
    #
    # 为什么需要它：哨兵机制只能发现「某个文件变了」，
    # 发现不了「某些文件被删了」—— 删掉之后其余文件的哨兵全部不变，
    # App 会判定「已是最新」，那些文件就永远留在设备上。
    # 递增这个号会触发 App 清空运行包目录后重新解压。
    revision = int(os.environ.get('DSH_PAYLOAD_REVISION', '1'))
    manifest = {'version': 4, 'revision': revision, 'parts': parts}
    mpath = os.path.join(OUT, 'manifest.json')
    with open(mpath, 'w') as f:
        json.dump(manifest, f, indent=2)
    print(f"\n  manifest.json: {os.path.getsize(mpath)} 字节")
    print(f"  分片合计: {sum(p['size'] for p in parts)/1048576:.1f}MB")


if __name__ == '__main__':
    main()
