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
import shutil
import shlex
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
# 从 dsh 分片里排除的内容 —— 它们在 Android 上永远不会被加载。
#
# 依据：sharp 被 App 用 Pillow 实现整体替换（node_modules/sharp/index.js 被覆盖），
# 而这些是 sharp 的 optionalDependencies（各平台的原生库）：
#   sharp-linux-arm64          glibc ELF，Android 的 bionic 加载不了
#   sharp-libvips-linux-arm64  glibc 原生库（18MB，最大的一块）
#   sharp-wasm32               WASM 回退实现，替换后不会走到
# 保留 @img/colour（纯 JS，96KB，保守起见）。
DSH_EXCLUDE = [
    './node_modules/@img/sharp-linux-arm64',
    './node_modules/@img/sharp-libvips-linux-arm64',
    './node_modules/@img/sharp-wasm32',
]

# 需要 App 从**已有安装**里删掉的路径（相对目标目录）。
# 哨兵发现不了「文件被删了」，靠这份清单补 —— 见 PayloadUpdate 的说明。
DSH_REMOVE = [
    'node_modules/@img/sharp-linux-arm64',
    'node_modules/@img/sharp-libvips-linux-arm64',
    'node_modules/@img/sharp-wasm32',
]

# 各分片的修订号：内容有实质变化（含只删不加）时递增。
#
# 只给 dsh 分片递增：它删掉了 27MB，重下约 18MB，净赚。
# tools 侧也有约 3.7MB 可删（npm 文档、补全脚本等），
# 但改动会牵连整个分片重下（tools-base 13.7MB），得不偿失，故不动。
PART_REVISION = {
    'dsh.tar.zst': 2,
}

PARTS = [
    ('dsh.tar.zst',          'dsh',   'lib/bin.js'),
    ('tools-base.tar.zst',   'tools', 'share/git-core/templates/description'),
    ('tools-libs.tar.zst',   'tools', 'lib/libwebp.so'),          # 本次新增该库
    ('tools-python.tar.zst', 'tools', 'lib/python3.14/site-packages/pypdf/__init__.py'),  # 本次新增 pypdf
    ('tools-npm.tar.zst',    'tools', 'lib/node_modules/npm/package.json'),
]


def part_entry(name, target, size, digest, sentinel, sent_size, sent_sha):
    """构造清单里的一个分片条目。

    revision：内容修订号，变化（含只删文件）时递增 —— 哨兵发现不了删除。
    remove：处理该分片前要删掉的相对路径，同样是给「哨兵看不见的删除」用的。
    """
    e = {
        'name': name, 'target': target, 'size': size, 'sha256': digest,
        'sentinel': {'path': sentinel, 'size': sent_size, 'sha256': sent_sha},
    }
    rev = PART_REVISION.get(name, 0)
    if rev:
        e['revision'] = rev
    if name == 'dsh.tar.zst' and DSH_REMOVE:
        e['remove'] = list(DSH_REMOVE)
    return e


def rev_note(name):
    """打印时补一句修订号说明。"""
    rev = PART_REVISION.get(name, 0)
    return f"  修订 {rev}" if rev else ""


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
            # 解压到临时目录后用 --exclude 重新打包。
            # 不用管道流式过滤：tar 无法同时从 stdin 读、向 stdout 写并过滤条目。
            # 排除清单里都是整目录，用 --exclude 最直接可靠。
            print(f"  重打包 {name}（排除 {len(DSH_EXCLUDE)} 项无用原生库）…")
            tmp = os.path.join(OUT, '.dsh-repack')
            if os.path.exists(tmp):
                shutil.rmtree(tmp)
            os.makedirs(tmp, exist_ok=True)
            r = subprocess.run(['bash', '-c',
                                'zstd -dc %s | tar -xf - -C %s'
                                % (shlex.quote(src), shlex.quote(tmp))],
                               capture_output=True, text=True)
            if r.returncode != 0:
                sys.exit(f"解压失败: {r.stderr[:400]}")
            # 记录排除项删掉了多少
            saved = 0
            for e in DSH_EXCLUDE:
                victim = os.path.join(tmp, e.lstrip('./'))
                if os.path.exists(victim):
                    saved += sum(os.path.getsize(os.path.join(dp, f))
                                 for dp, _dn, fn in os.walk(victim) for f in fn)
                    shutil.rmtree(victim)
            if os.path.exists(out):
                os.remove(out)
            cmd = ("tar %s -cf - -C %s . | zstd -19 -T0 -q -o %s"
                   % (' '.join(shlex.quote('--exclude=' + x.lstrip('./')) for x in DSH_EXCLUDE),
                      shlex.quote(tmp), shlex.quote(out)))
            r = subprocess.run(['bash', '-c', cmd], capture_output=True, text=True)
            if r.returncode != 0:
                sys.exit(f"重打包失败: {r.stderr[:400]}")
            shutil.rmtree(tmp)
            print(f"    已排除 {saved/1048576:.1f}MB 无用原生库")
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
            parts.append(part_entry(name, target, size, digest, sentinel, sent_size, sent_sha))
            print(f"  {name:<22} {size/1048576:6.1f}MB  哨兵 {sentinel} ({sent_size}B)"
                  + rev_note(name))
            continue

        sent_sha = sha256_file(sent_path)
        sent_size = os.path.getsize(sent_path)
        parts.append(part_entry(name, target, size, digest, sentinel, sent_size, sent_sha))
        print(f"  {name:<22} {size/1048576:6.1f}MB  哨兵 {sentinel} ({sent_size}B)"
              + rev_note(name))

    # revision：**内容修订号**，任何实质性变化（包括只删文件）都要递增。
    #
    # 为什么需要它：哨兵机制只能发现「某个文件变了」，
    # 发现不了「某些文件被删了」—— 删掉之后其余文件的哨兵全部不变，
    # App 会判定「已是最新」，那些文件就永远留在设备上。
    # 递增这个号会触发 App 清空运行包目录后重新解压。
    manifest = {'version': 4, 'parts': parts}
    mpath = os.path.join(OUT, 'manifest.json')
    with open(mpath, 'w') as f:
        json.dump(manifest, f, indent=2)
    print(f"\n  manifest.json: {os.path.getsize(mpath)} 字节")
    print(f"  分片合计: {sum(p['size'] for p in parts)/1048576:.1f}MB")


if __name__ == '__main__':
    main()
