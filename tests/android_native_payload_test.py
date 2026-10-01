"""Payload native binaries must be loadable by an Android app, not just the right CPU.

Background
----------
`node-pty` ships no official Android prebuild. The payload builder filled both
`linux-arm64` and `android-arm64` slots with the same file copied from
`node-pty/prebuilds/linux-arm64` — a **Termux** build. It is a valid AArch64
ELF, so the previous magic-number/architecture assertions all passed, but it
links `libutil.so.1`, `libstdc++.so.6` and `ld-linux-aarch64.so.1`, none of
which exist in an ordinary Android app. `dlopen` therefore always failed and
the interactive terminal never worked on device.

node-pty's loader hides this: it tries several paths and rethrows only the last
error, so the log said "Cannot find module './prebuilds/android-arm64/pty.node'"
while the binary was sitting right there.

These tests pin the dependency check so the same mistake cannot ship again.
"""
import importlib.util
import pathlib
import struct
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent
# The build workspace stages only a few scripts, so fall back like the Java tests do.
script = next((c for c in (ROOT / 'scripts/prepare_core_payload.py',
                           ROOT / 'prepare_core_payload.py') if c.exists()), None)
prepare = None
if script is not None:
    spec = importlib.util.spec_from_file_location('prepare_core_payload', script)
    prepare = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(prepare)


def requires_builder(test):
    return unittest.skipIf(prepare is None,
                          'prepare_core_payload.py is not part of this build workspace')(test)

PT_LOAD, PT_DYNAMIC = 1, 2


def build_elf(machine=0xB7, needed=(), bits=64):
    """Assemble a minimal ELF object whose DT_NEEDED lists `needed`.

    Program headers carry p_offset and p_vaddr as separate fields, which is
    exactly what the checker under test has to map correctly. Field offsets
    follow the real ELF64/ELF32 header layout:
      0x10 e_type  0x12 e_machine  0x14 e_version
      0x18 e_entry 0x20 e_phoff    0x28 e_shoff   0x30 e_flags
      0x34 e_ehsize 0x36 e_phentsize 0x38 e_phnum
    """
    is64 = bits == 64
    names = b''.join(n.encode() + b'\0' for n in needed)
    strtab_vaddr = 0x4000
    dyn_vaddr = 0x3000
    phdr = '<IIQQQQQQ' if is64 else '<IIIIIIII'
    ehsize, phsize = (64, 56) if is64 else (52, 32)
    ident = b'\x7fELF' + bytes([2 if is64 else 1, 1, 1, 0]) + bytes(8)
    # DT_NEEDED carries a **byte offset into DT_STRTAB**, not an index.
    offsets, cursor = [], 0
    for name in needed:
        offsets.append(cursor)
        cursor += len(name.encode()) + 1
    dyn = [(1, off) for off in offsets]
    dyn += [(5, strtab_vaddr), (10, len(names)), (0, 0)]
    fmt = '<QQ' if is64 else '<II'
    dyn_blob = b''.join(struct.pack(fmt, tag, val) for tag, val in dyn)

    phoff = ehsize                       # program header table follows the header
    dyn_off = phoff + phsize * 2         # .dynamic follows the two headers
    str_off = dyn_off + len(dyn_blob)    # .dynstr follows .dynamic

    head = ident + struct.pack('<HHI', 2, machine, 1)
    head += struct.pack('<QQQ' if is64 else '<III', 0, phoff, 0)  # e_entry, e_phoff, e_shoff
    head += struct.pack('<IHHHHHH', 0, ehsize, phsize, 2, 0, 0, 0)

    # One PT_LOAD mapping file [0, total) to vaddr [base, base+total) so that the
    # vaddr->offset mapping the checker performs is exercised for real.
    total = str_off + len(names)
    base = strtab_vaddr - str_off
    load = struct.pack(phdr, PT_LOAD, 4, 0, base, 0, total, total, 0x1000)[:phsize]
    dyn_ph = struct.pack(phdr, PT_DYNAMIC, 6, dyn_off, base + dyn_off, 0,
                         len(dyn_blob), len(dyn_blob), 8)[:phsize]

    blob = bytearray(head + load + dyn_ph)
    blob.extend(b'\0' * (dyn_off - len(blob)))
    blob.extend(dyn_blob)
    blob.extend(b'\0' * (str_off - len(blob)))
    blob.extend(names)
    return bytes(blob)


@requires_builder
class NeededLibrariesTest(unittest.TestCase):
    def write(self, directory, blob, name='pty.node'):
        path = pathlib.Path(directory) / name
        path.write_bytes(blob)
        return path

    def test_reads_dt_needed_from_64bit(self):
        with tempfile.TemporaryDirectory() as d:
            p = self.write(d, build_elf(needed=['libc.so', 'liblog.so']))
            self.assertEqual(prepare.needed_libraries(p), ['libc.so', 'liblog.so'])

    def test_no_needed_entries_yields_empty_list(self):
        with tempfile.TemporaryDirectory() as d:
            p = self.write(d, build_elf(needed=[]))
            self.assertEqual(prepare.needed_libraries(p), [])

    def test_rejects_non_elf(self):
        with tempfile.TemporaryDirectory() as d:
            p = self.write(d, b'MZ' + b'\0' * 200)
            with self.assertRaises(ValueError):
                prepare.needed_libraries(p)

    def test_rejects_wrong_architecture(self):
        with tempfile.TemporaryDirectory() as d:
            p = self.write(d, build_elf(machine=0x3E))   # EM_X86_64
            with self.assertRaises(ValueError):
                prepare.needed_libraries(p)


@requires_builder
class AndroidLoadableTest(unittest.TestCase):
    def test_accepts_android_native_build(self):
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d) / 'pty.node'
            p.write_bytes(build_elf(needed=['libc.so', 'liblog.so', 'libm.so', 'libdl.so']))
            deps = prepare.assert_android_loadable(p, 'android PTY')
            self.assertNotIn('libutil.so.1', deps)

    def test_rejects_termux_build(self):
        """The exact regression: a Termux prebuild must never reach a device."""
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d) / 'pty.node'
            p.write_bytes(build_elf(needed=[
                'libutil.so.1', 'libstdc++.so.6', 'libgcc_s.so.1',
                'libpthread.so.0', 'libc.so.6', 'ld-linux-aarch64.so.1']))
            with self.assertRaises(ValueError) as ctx:
                prepare.assert_android_loadable(p, 'android PTY')
            self.assertIn('libutil.so.1', str(ctx.exception))


@requires_builder
class PayloadBuilderTest(unittest.TestCase):
    """The builder must take the Android fork, never the Termux prebuild."""

    def source(self):
        text = script.read_text()
        start = text.index('def patch_android')
        return text[start:text.index('def obsolete_paths')]

    def test_does_not_use_termux_prebuild_as_source(self):
        self.assertNotIn("prebuilds/linux-arm64/pty.node", self.source())

    def test_sources_the_android_fork(self):
        self.assertIn('@mmmbuto/node-pty-android-arm64/prebuilds/android-arm64/pty.node',
                      self.source())

    def test_verifies_every_copy_it_makes(self):
        """Both slots must be re-checked: a copy error silently breaks the terminal."""
        self.assertIn('assert_android_loadable(dest', self.source())



class PruneUnusableNativesTest(unittest.TestCase):
    """The pruner must be driven by ELF facts, never by a hardcoded platform list."""

    @requires_builder
    def test_keeps_android_and_termux_pty_slots(self):
        import shutil
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            mods = pathlib.Path(d) / 'node_modules'
            # Windows/darwin slots ship binaries for a different CPU, which is
            # what makes them dead weight; the two arm64 slots are kept.
            archs = {'android-arm64': 0xB7, 'linux-arm64': 0xB7, 'win32-x64': 0x3E}
            for slot, machine in archs.items():
                p = mods / 'node-pty/prebuilds' / slot
                p.mkdir(parents=True)
                (p / 'pty.node').write_bytes(build_elf(machine=machine))
            removed = prepare.prune_unusable_natives(mods)
            kept = sorted(p.name for p in (mods / 'node-pty/prebuilds').iterdir())
            self.assertEqual(kept, ['android-arm64', 'linux-arm64'])
            self.assertIn('node-pty/prebuilds/win32-x64', removed)

    @requires_builder
    def test_removes_desktop_lib_dependency(self):
        with tempfile.TemporaryDirectory() as d:
            mods = pathlib.Path(d) / 'node_modules'
            p = mods / '@koromix/koffi-linux-arm64/linux_arm64'
            p.mkdir(parents=True)
            (p / 'koffi.node').write_bytes(build_elf(needed=['libc.so.6', 'libstdc++.so.6']))
            removed = prepare.prune_unusable_natives(mods)
            self.assertFalse(p.exists())
            self.assertEqual(len(removed), 1)

    @requires_builder
    def test_keeps_android_native_module(self):
        with tempfile.TemporaryDirectory() as d:
            mods = pathlib.Path(d) / 'node_modules'
            p = mods / '@mmmbuto/node-pty-android-arm64/prebuilds/android-arm64'
            p.mkdir(parents=True)
            (p / 'pty.node').write_bytes(build_elf(needed=['libc.so', 'liblog.so']))
            prepare.prune_unusable_natives(mods)
            self.assertTrue((p / 'pty.node').exists())

    @requires_builder
    def test_does_not_touch_non_platform_directories(self):
        with tempfile.TemporaryDirectory() as d:
            mods = pathlib.Path(d) / 'node_modules'
            p = mods / 'some-random-package/lib'
            p.mkdir(parents=True)
            (p / 'thing.node').write_bytes(build_elf(needed=['libc.so.6']))
            prepare.prune_unusable_natives(mods)
            self.assertTrue((p / 'thing.node').exists())

    @requires_builder
    def test_handles_multiple_binaries_in_one_directory(self):
        """rglob is lazy: deleting a directory mid-iteration must not crash."""
        with tempfile.TemporaryDirectory() as d:
            mods = pathlib.Path(d) / 'node_modules'
            p = mods / 'node-pty/prebuilds/win32-x64'
            p.mkdir(parents=True)
            for name in ('pty.node', 'conpty.node', 'conpty_console_list.node'):
                (p / name).write_bytes(build_elf(machine=0x3E))
            removed = prepare.prune_unusable_natives(mods)   # must not raise
            self.assertIn('node-pty/prebuilds/win32-x64', removed)

if __name__ == '__main__':
    unittest.main()
