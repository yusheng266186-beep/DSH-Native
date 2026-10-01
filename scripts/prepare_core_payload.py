#!/usr/bin/env python3
"""Build only the DSH shard; retain the verified Android binaries and tools.

Inputs are an extracted, SHA-verified previous runtime and its pinned manifest.
The new CLI tarball and dependency tree are pinned by runtime/core-*.json.
Publication must upload all shards/checksums before uploading manifest.json.
"""
import argparse
import base64
import hashlib
import json
import pathlib
import shutil
import struct
import subprocess
import tarfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def replace_once(path, before, after):
    text = path.read_text()
    if text.count(before) != 1:
        raise ValueError(f'Upstream patch anchor changed: {path}')
    path.write_text(text.replace(before, after))


def needed_libraries(path):
    """返回 ELF 的 DT_NEEDED 列表。

    为什么必须查依赖而不是只看架构
    ------------------------------
    `linux-arm64` 与 `android-arm64` 两个槽位曾被填成同一个文件：Termux 编译的
    node-pty 预编译。它是正确的 AArch64 ELF，架构断言全部通过，但 `dlopen` 时
    要求 `libutil.so.1` —— 那是 Termux 自带的库，普通 Android 应用没有
    （Android 12 起已从公共 bionic 移除）。于是终端在真机上必然失败，而构建期
    毫无察觉，node-pty 的加载器又只报最后一个 "Cannot find module"，把真实原因
    盖住了。架构正确不等于能加载，所以这里直接检查依赖。
    """
    blob = path.read_bytes()
    if blob[:4] != b'\x7fELF':
        raise ValueError(f'not an ELF object: {path}')
    is64 = blob[4] == 2
    if not is64:
        raise ValueError(f'expected a 64-bit ELF: {path}')
    if struct.unpack_from('<H', blob, 0x12)[0] != 0xB7:
        raise ValueError(f'not AArch64 (EM_AARCH64): {path}')

    # Prefer the program headers: they stay valid for stripped binaries, where the
    # section table (which npm prebuilds sometimes reduce) may be absent. PT_DYNAMIC
    # carries the virtual address of .dynamic, and PT_LOAD maps it to a file offset.
    e_phoff = struct.unpack_from('<Q' if is64 else '<I', blob, 0x20)[0]
    e_phentsize, e_phnum = struct.unpack_from('<HH', blob, 0x36)
    phdr = '<IIQQQQQQ' if is64 else '<IIIIIIII'
    PT_LOAD, PT_DYNAMIC = 1, 2
    loads = []
    dynamic_vaddr = None
    dynamic_filesz = 0
    for i in range(e_phnum):
        fields = struct.unpack_from(phdr, blob, e_phoff + i * e_phentsize)
        if fields[0] == PT_LOAD:
            # Elf64_Phdr layout: type, flags, offset, vaddr, paddr, filesz, memsz, align.
            loads.append((fields[3], fields[6], fields[2], fields[5]))  # vaddr, memsz, offset, filesz
        elif fields[0] == PT_DYNAMIC:
            dynamic_vaddr, dynamic_filesz = fields[3], fields[5]
    if dynamic_vaddr is None:
        return []

    def to_offset(vaddr):
        for vaddr_lo, memsz, off, filesz in loads:
            if vaddr_lo <= vaddr < vaddr_lo + max(memsz, filesz):
                return off + (vaddr - vaddr_lo)
        raise ValueError(f'cannot map vaddr 0x{vaddr:x} in {path}')

    dyn_offset = to_offset(dynamic_vaddr)

    # DT_STRTAB holds a virtual address too; convert it the same way.
    entry = '<QQ' if is64 else '<II'
    step = struct.calcsize(entry)
    DT_NULL, DT_NEEDED, DT_STRTAB, DT_STRSZ = 0, 1, 5, 10
    needed, strtab_vaddr = [], None
    for pos in range(dyn_offset, dyn_offset + dynamic_filesz, step):
        tag, value = struct.unpack_from(entry, blob, pos)
        if tag == DT_NULL:
            break
        if tag == DT_NEEDED:
            needed.append(value)
        elif tag == DT_STRTAB:
            strtab_vaddr = value
    if strtab_vaddr is None or not needed:
        return []
    str_offset = to_offset(strtab_vaddr)
    names = []
    for value in needed:
        start = str_offset + value
        end = blob.index(b'\0', start)
        names.append(blob[start:end].decode())
    return names


def assert_android_loadable(path, label):
    """校验这个二进制在普通 Android 应用里真的能 dlopen。

    允许依赖 `libc.so`/`libdl.so`/`libm.so`/`libandroid.so` 等系统公开库，
    但 `libutil.so` 只有 Termux 有 —— 见到它就是选错了预编译。
    """
    forbidden = {'libutil.so', 'libutil.so.1'}
    linked = needed_libraries(path)
    bad = forbidden.intersection(linked)
    if bad:
        raise ValueError(
            f'{label}: {path.name} links {sorted(bad)}, which only exists in Termux; '
            'it cannot be dlopened by an Android app. Ship an Android-native build.')
    return linked


def patch_android(runtime, base):
    modules = runtime / 'node_modules'
    # node-pty 没有官方 Android 预编译。用 @mmmbuto/node-pty-android-arm64 ——
    # 它是针对 Android/bionic 编译的分支。**不要**退回
    # `node-pty/prebuilds/linux-arm64`：那是 Termux 变体，依赖 libutil.so.1。
    native = base / 'node_modules/@mmmbuto/node-pty-android-arm64/prebuilds/android-arm64/pty.node'
    native_deps = assert_android_loadable(native, 'android PTY')
    for platform in ('linux-arm64', 'android-arm64'):
        dest = modules / 'node-pty/prebuilds' / platform / 'pty.node'
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(native, dest)
        # 每个落点都复核一次：复制错误会让终端重新静默失效。
        assert_android_loadable(dest, f'node-pty {platform}')
    # dirs_exist_ok：构建重试与 CI 重入会在同一个输出目录上再跑一遍，
    # 而 @mmmbuto 已经被上一轮复制进来了。没有这个参数时 copytree 直接
    # FileExistsError，payload 构建会在重试时失败。覆盖写是安全的：
    # 源始终是已验证的基底运行包。
    shutil.copytree(base / 'node_modules/@mmmbuto', modules / '@mmmbuto',
                    dirs_exist_ok=True)
    shutil.copyfile(base / 'node_modules/node-addon-require-builtin/lib/index.js',
                    modules / 'node-addon-require-builtin/lib/index.js')

    # Previous payload swallowed native addon failures on every platform. Keep
    # the Android fallback, but preserve actual native locks on desktop/CI.
    flock = modules / '@deepseek-ai/node-addon-system/lib/flock.js'
    replace_once(flock, "import { createRequire } from 'node:module';",
                 "import { createRequire } from 'node:module';\n"
                 "import { existsSync } from 'node:fs';")
    replace_once(flock, '    const { platform, arch } = process;',
                 '    const { platform, arch } = process;\n'
                 "    if (platform === 'android' || (platform === 'linux' && arch === 'arm64'\n"
                 "        && existsSync('/system/bin/linker64'))) {\n"
                 '        // Single managed Android runtime; native flock has no bionic build.\n'
                 "        binding = { tryLock: (_fd, done) => done(0) };\n"
                 '        return binding;\n'
                 '    }')

    # Retain exclusive publication, including on filesystems without hardlinks.
    # COPYFILE_EXCL refuses to overwrite a concurrently published generation;
    # ordinary link errors still propagate instead of being silently swallowed.
    persistence = modules / '@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js'
    replace_once(persistence, 'import { link, lstat,',
                 'import { copyFile as androidCopyFile, link, lstat,')
    replace_once(persistence, 'import { readdirSync } from "node:fs";',
                 'import { readdirSync, constants as androidFsConstants } from "node:fs";')
    replace_once(persistence, '\t\tawait internals.fs.link(staged, currentPath);',
                 '\t\ttry { await internals.fs.link(staged, currentPath); } catch (error) {\n'
                 "            if (!['EPERM', 'EXDEV', 'ENOSYS', 'EOPNOTSUPP'].includes(error?.code)) throw error;\n"
                 '            await androidCopyFile(staged, currentPath, androidFsConstants.COPYFILE_EXCL);\n'
                 '        }')

    # sharp 被 App 用 Pillow 整体替换（见 payload/sharp-android.js），因此它所有
    # 平台变体都不会被加载。
    for platform in ('linux-arm64', 'linux-x64', 'wasm32'):
        for name in (f'sharp-{platform}', f'sharp-libvips-{platform}'):
            shutil.rmtree(modules / '@img' / name, ignore_errors=True)

    removed = prune_unusable_natives(modules)
    return digest(native), native_deps, removed


# 只处理**目录名本身就带平台标记**的包，也就是 npm 那种
# `<pkg>-<platform>-<arch>` 的可选依赖。其余一律保留。
#
# 特别说明：`node-addon-require-builtin` 本身是纯 JS 垫片（patch/01），
# 但它的可选原生依赖目录 `node-addon-require-builtin-<platform>-<arch>`
# 仍然会被扫到并删掉 —— 这正是期望行为，垫片不碰那些文件。
# 判据是**目录名**，不是「包是否重要」，所以不会误删垫片本身。
NATIVE_DIR_PATTERNS = (
    'node-pty/prebuilds/',
    '@koromix/koffi-',
    '@deepseek-ai/node-addon-system-',
    'node-addon-require-builtin-',
    'sherpa-onnx-',
)


def machine_is_aarch64(path):
    """该 ELF 是否为 AArch64（读 e_machine，不抛异常）。"""
    try:
        with open(path, 'rb') as handle:
            header = handle.read(20)
        if header[:4] != b'\x7fELF':
            return False
        return struct.unpack_from('<H', header, 0x12)[0] == 0xB7
    except OSError:
        return False


def detect_platform(rel_dir):
    """从相对路径里认出平台标记。

    node-pty 走 ``prebuilds/<platform>/``，其余包走 ``<pkg>-<platform>-<arch>``
    （如 ``koffi-linux-x64``、``node-addon-system-linux-arm64``、
    ``node-addon-require-builtin-linux-x64-gnu``）。两种形态都要认。
    """
    platforms = ('android-arm64', 'linux-arm64', 'linux-x64',
                 'darwin-arm64', 'darwin-x64', 'win32-arm64', 'win32-x64',
                 'wasm32')
    parts = rel_dir.split('/')
    # node-pty: prebuilds/<platform>/
    if len(parts) >= 2 and parts[-2] == 'prebuilds':
        return parts[-1]
    # 其余包两种命名都要认：
    #   包目录用连字符  koffi-linux-arm64 / node-addon-system-linux-x64
    #   子目录用下划线  linux_arm64 / linux_x64
    #   另有一种带 -gnu 后缀 node-addon-require-builtin-linux-x64-gnu
    for part in parts:
        for plat in platforms:
            if part == plat or part.startswith(plat + '-'):
                return plat
            if part == plat.replace('-', '_'):
                return plat
            # 包目录名平台标记在**后缀**：
            #   node-addon-system-linux-x64   -> linux-x64
            #   node-addon-require-builtin-linux-x64-gnu -> linux-x64
            if part.endswith('-' + plat) or part.endswith('-' + plat + '-gnu'):
                return plat
    return None


def prune_unusable_natives(modules):
    """删掉在 Android/arm64 上**确定**加载不了的原生二进制。

    为什么按「可加载性」判定，而不是写死平台名
    ------------------------------------------
    上一版是写死的 `sharp-linux-arm64` 这类清单。构建环境从 arm64 换成 GitHub
    Actions 的 x86_64 runner 之后，装进来的是 `-linux-x64`，清单完全失效，
    18MB 的 glibc libvips 一直混在运行包里。同类错误已经吃过一次，
    所以这里改成读 ELF 事实：**不是 AArch64，或链了 Termux/桌面才有的库，
    就一定是死重**。这样换 runner、换 Node 版本都不会再悄悄失效。

    返回被删除的路径（相对 node_modules），供 BUILD-INFO 与清单记录。
    """
    # 保留 **linux-x64 / linux-arm64(glibc)** 这类桌面 Linux 构建。
    #
    # 曾经的错：把「不是 AArch64」一律当死重，把 linux-x64 也删了。
    # 结果 CI 的 check_model_consumer.sh（真实运行包消费验证，跑在 x64 上）
    # 直接 MODULE_NOT_FOUND —— node-addon-system 的 flock.js 在非 Android
    # 平台仍会尝试加载 linux-x64 绑定。
    #
    # 也就是说：判断标准不是「这台手机上用不用得上」，而是
    # **「这个二进制在本项目的任何一条执行路径上还可能不会被加载」**。
    # 运行包不只被 App 消费，CI 的真实消费验证也会跑它。
    keep_platforms = {'android-arm64', 'linux-arm64', 'linux-x64'}
    desktop_only = {'libutil.so', 'libutil.so.1', 'libc.so.6', 'ld-linux-aarch64.so.1'}
    removed = []
    # rglob 是惰性迭代：rmtree 之后仍会继续产出同一目录下已被删除的文件。
    # 记录已处理目录，避免对不存在的路径再读一次。
    handled = set()
    for path in sorted(modules.rglob('*.node')):
        directory = path.parent
        if directory in handled:
            continue
        rel_dir = directory.relative_to(modules).as_posix()
        if not any(rel_dir.startswith(prefix) for prefix in NATIVE_DIR_PATTERNS):
            continue
        # 平台标记在**目录名本身**：node-pty 是 prebuilds/<platform>/，
        # 其余包是 <pkg>-<platform>-<arch>。统一从路径里认 platform-<arch> 段
        # 或 prebuilds 后的那一段。
        platform = detect_platform(rel_dir)

        # Android 槽位里的 AArch64 二进制若链接 Termux/桌面库，必然加载不了。
        # 这一条必须**先于** keep_platforms 判定：linux-arm64 名义上是保留
        # 槽位，但带 libutil.so 就同样不能用。
        if platform in ('android-arm64', 'linux-arm64') and machine_is_aarch64(path):
            try:
                if desktop_only & set(needed_libraries(path)):
                    handled.add(directory)
                    shutil.rmtree(directory, ignore_errors=True)
                    removed.append(rel_dir)
                    continue
            except (ValueError, OSError):
                pass

        if platform and platform in keep_platforms:
            continue
        try:
            deps = needed_libraries(path)
        except (ValueError, OSError):
            # 不是本机架构的 ELF（mach-o、arm64 之外的）：任何路径都用不上。
            handled.add(directory)
            shutil.rmtree(directory, ignore_errors=True)
            removed.append(rel_dir)
            continue
        if desktop_only & set(deps):
            # 依赖桌面/Termux 库 -> 在 **Android** 上加载不了。
            #
            # 但只有当这个二进制是 Android 专属槽位时才删：linux-x64 的 glibc
            # 构建依赖 libc.so.6 是**正常**的，CI 的真实消费验证跑在 x64 上，
            # 删掉会让 node-addon-system 的 flock.js MODULE_NOT_FOUND。
            # 判断依据是 ELF 架构：x86_64 的构建归 CI 用，保留。
            is_android_slot = platform in ('android-arm64', 'linux-arm64') \
                and machine_is_aarch64(path)
            if not is_android_slot:
                continue
            handled.add(directory)
            shutil.rmtree(directory, ignore_errors=True)
            removed.append(rel_dir)
    return sorted(set(removed))


def obsolete_paths(base, runtime):
    paths = []
    # Delete only previously shipped files. User profiles/workspaces are outside
    # this directory; files newly installed by the user are not enumerated here.
    for path in sorted(base.rglob('*')):
        rel = path.relative_to(base)
        if (path.is_file() or path.is_symlink()) and not (runtime / rel).exists():
            paths.append(rel.as_posix())
    paths.append('node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js.dshorig')
    return sorted(set(paths))


def expanded_size(runtime):
    # Round files to 4KiB and include directories/symlinks as allocation costs.
    return sum(4096 if p.is_dir() or p.is_symlink() else
               max(4096, ((p.stat().st_size + 4095) // 4096) * 4096)
               for p in runtime.rglob('*'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=pathlib.Path, required=True)
    parser.add_argument('--previous-manifest', type=pathlib.Path, required=True)
    parser.add_argument('--out', type=pathlib.Path, required=True)
    parser.add_argument('--prepared-runtime', type=pathlib.Path,
                        help='Use an already npm-ci prepared clean runtime')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    source = json.loads((ROOT / 'runtime/core-source.json').read_text())
    manifest = json.loads(args.previous_manifest.read_text())
    runtime = args.prepared_runtime
    if runtime is None:
        runtime = args.out / 'runtime'
        runtime.mkdir()  # Never overwrite a populated install.
        archive = args.out / 'upstream-cli.tgz'
        urllib.request.urlretrieve(source['tarball'], archive)
        expected = base64.b64decode(source['integrity'].removeprefix('sha512-'))
        assert hashlib.sha512(archive.read_bytes()).digest() == expected
        with tarfile.open(archive) as tar:
            tar.extractall(runtime, filter='data')
        for path in (runtime / 'package').iterdir():
            shutil.move(str(path), runtime / path.name)
        (runtime / 'package').rmdir()
        shutil.copyfile(ROOT / 'runtime/core-package-lock.json', runtime / 'package-lock.json')
        subprocess.run(['npm', 'ci', '--prefix', str(runtime), '--omit=dev',
                        '--ignore-scripts', '--no-audit', '--no-fund'], check=True)
    assert json.loads((runtime / 'package.json').read_text())['version'] == source['version']
    for pkg in (runtime / 'node_modules/@deepseek-ai').glob('dsh-*/package.json'):
        assert json.loads(pkg.read_text())['version'] == source['version'], pkg
    native_sha, native_deps, pruned = patch_android(runtime, args.base)
    removals = obsolete_paths(args.base, runtime)
    shard = args.out / 'dsh.tar.zst'
    # Fixed owner/timestamp and sorted paths make archives independent of npm's
    # extraction times. GNU tar long paths are handled by App's unpack.js.
    tar = subprocess.Popen(['tar', '--sort=name', '--mtime=@0', '--owner=0',
                            '--group=0', '--numeric-owner', '-cf', '-', '-C', str(runtime), '.'],
                           stdout=subprocess.PIPE)
    zstd = subprocess.run(['zstd', '-10', '-T2', '-q', '-f', '-o', str(shard)], stdin=tar.stdout)
    tar.stdout.close()
    assert tar.wait() == 0 and zstd.returncode == 0
    old = next(p for p in manifest['parts'] if p['name'] == 'dsh.tar.zst')
    sentinel = runtime / 'lib/bin.js'
    new = dict(old, size=shard.stat().st_size, sha256=digest(shard),
               revision=old.get('revision', 0) + 1, remove=sorted(set(old.get('remove', []) + removals)),
               unpacked_size=expanded_size(runtime),
               sentinel=dict(path='lib/bin.js', size=sentinel.stat().st_size, sha256=digest(sentinel)))
    manifest['parts'] = [new if p['name'] == 'dsh.tar.zst' else p for p in manifest['parts']]
    (args.out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    info = f"DSH CLI: {source['version']}\nBase payload: {source['base_payload']}\n" \
           f"DSH revision: {new['revision']}\nAndroid PTY SHA256: {native_sha}\n" \
           f"Android PTY DT_NEEDED: {' '.join(native_deps) or '(none)'}\n" \
           f"Pruned unusable natives: {len(pruned)}\n" \
           f"npm lock SHA256: {digest(ROOT / 'runtime/core-package-lock.json')}\n"
    (args.out / 'BUILD-INFO.txt').write_text(info)
    print(info, end='')
    print(f"DSH shard: {new['size']} bytes; expanded: {new['unpacked_size']}; "
          f"removals: {len(new['remove'])}")
    if pruned:
        print('Pruned (unloadable on Android/arm64):')
        for name in pruned:
            print(f'  - {name}')
    print('manifest SHA256: ' + digest(args.out / 'manifest.json'))


if __name__ == '__main__':
    main()
