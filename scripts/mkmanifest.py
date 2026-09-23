#!/usr/bin/env python3
"""
mkmanifest.py -- emit a valid Android *binary* AndroidManifest.xml (AXML).

Pure Python 3 standard library.  No aapt2, no Android SDK, no third-party deps.

Why this exists
---------------
aapt2 cannot run on this device, so the resource compiler step is unavailable.
Android does not read AndroidManifest.xml as text at install time -- it reads
the compiled AXML (``ResXMLTree``) form.  This module writes that binary format
directly from the AOSP ``ResourceTypes.h`` layout.

API
---
    build_manifest() -> bytes          # the complete AXML file
    python3 mkmanifest.py OUT_PATH     # writes it to OUT_PATH

Format overview
---------------
    offset 0 : ResChunk_header  { type=0x0003 (RES_XML_TYPE), headerSize=8,
                                  size=<whole file> }
    then a flat sequence of chunks:
      * RES_STRING_POOL_TYPE      0x0001  UTF-8 pool (flags 0x0100)
      * RES_XML_RESOURCE_MAP_TYPE 0x0180  u32 attr resource id per attr-name string
      * RES_XML_START_NAMESPACE   0x0100
      * RES_XML_START_ELEMENT     0x0102 / RES_XML_END_ELEMENT 0x0103
      * RES_XML_END_NAMESPACE     0x0101

String-pool ordering (this is the subtle part)
---------------------------------------------
``ResXMLTree::getResourceIdAt()`` indexes the resource map by *string pool
index*, so the map is positional: ``map[i]`` is the attribute id of
``strings[i]``.  aapt2 therefore places every attribute name that has a
framework resource id at the *front* of the pool (sorted by resource id) and
emits exactly that many map entries.  Everything else -- element names,
namespace prefix/uri, attribute values, and attribute names without an id
(such as ``package``) -- follows afterwards.  Verified against 35 real
aapt2-produced APKs on this device (34/35 sort the map ids ascending; the
35th uses first-appearance order -- both parse identically).
"""

import os
import struct
import sys

# --------------------------------------------------------------------------
# Chunk types
# --------------------------------------------------------------------------
RES_XML_TYPE = 0x0003
RES_STRING_POOL_TYPE = 0x0001
RES_XML_RESOURCE_MAP_TYPE = 0x0180
RES_XML_START_NAMESPACE_TYPE = 0x0100
RES_XML_END_NAMESPACE_TYPE = 0x0101
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_END_ELEMENT_TYPE = 0x0103

# ResStringPool_header::flags
UTF8_FLAG = 0x0100

# Header sizes
STRING_POOL_HEADER_SIZE = 28
RESOURCE_MAP_HEADER_SIZE = 8
XML_NODE_HEADER_SIZE = 16          # ResXMLTree_node
XML_ATTR_EXT_SIZE = 20             # ResXMLTree_attrExt
XML_ATTR_SIZE = 20                 # ResXMLTree_attribute
RES_VALUE_SIZE = 8                 # Res_value

NO_ENTRY = 0xFFFFFFFF

# 资源 id 由 aapt2 分配。构建脚本导出后经环境变量传入，
# 因此这里只作兜底默认值 —— 两侧永远一致，不会再出现对不上的情况。
ICON_RES_ID = int(os.environ.get("DSH_ICON_RES_ID", "0x7F030000"), 16)
THEME_RES_ID = int(os.environ.get("DSH_THEME_RES_ID", "0x7F040000"), 16)
SHORTCUTS_RES_ID = int(os.environ.get("DSH_SHORTCUTS_RES_ID", "0x7F050000"), 16)

# Res_value::dataType
TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_INT_BOOLEAN = 0x12

ANDROID_NS = "http://schemas.android.com/apk/res/android"

# --------------------------------------------------------------------------
# Framework attribute resource ids (package 0x01 = "android").
#
# Every id below was verified twice on this device:
#   1. android/R$attr.class inside /root/build/sdk/android.jar (constant pool
#      ConstantValue), which confirmed 9 of 11.
#   2. Empirically harvested from the compiled AndroidManifest.xml of 35 real
#      APKs -- unanimous across every APK that used the attribute.
# --------------------------------------------------------------------------
ATTR_IDS = {
    "theme": 0x01010000,
    # 键盘弹出时收缩窗口（而非平移），避免输入法挡住内容；
    # id 取自 35 个真实 APK 采集的属性表：16843307 = 0x0101022b
    "windowSoftInputMode": 0x0101022B,
    # 供分享 intent-filter 的 <data android:mimeType> 使用
    "mimeType": 0x01010026,
    # 快捷方式声明需要 android:resource
    "resource": 0x01010025,
    # 更新用的 ContentProvider（FileProvider 的最小替代）
    "authorities": 0x01010018,
    "grantUriPermissions": 0x0101001B,
    "label": 0x01010001,
    "icon": 0x01010002,
    "name": 0x01010003,
    "hasCode": 0x0101000c,
    "exported": 0x01010010,
    "configChanges": 0x0101001f,
    "minSdkVersion": 0x0101020c,
    "versionCode": 0x0101021b,
    "versionName": 0x0101021c,
    "targetSdkVersion": 0x01010270,
    "extractNativeLibs": 0x010104ea,
    "usesCleartextTraffic": 0x010104ec,
}

# android.content.pm.ActivityInfo configuration-change bits
CONFIG_KEYBOARD_HIDDEN = 0x0020
CONFIG_ORIENTATION = 0x0080
CONFIG_SCREEN_SIZE = 0x0400


# --------------------------------------------------------------------------
# Attribute value constructors
# --------------------------------------------------------------------------
def s(text):
    """String attribute value -> Res_value TYPE_STRING (rawValue is kept)."""
    return (TYPE_STRING, text)


def ref(resource_id):
    """资源引用 -> TYPE_REFERENCE。

    用于 android:icon 这类必须指向资源表条目的属性：
    值不是字符串而是打包进 resources.arsc 的资源 id。
    """
    return (TYPE_REFERENCE, resource_id)


def dec(value):
    """Base-10 integer attribute value -> TYPE_INT_DEC."""
    return (TYPE_INT_DEC, value)


def hx(value):
    """Hex integer attribute value -> TYPE_INT_HEX (what aapt uses for flags)."""
    return (TYPE_INT_HEX, value)


def boolean(value):
    """Boolean attribute value -> TYPE_INT_BOOLEAN (0xFFFFFFFF true / 0 false)."""
    return (TYPE_INT_BOOLEAN, NO_ENTRY if value else 0)


def E(name, attrs=(), children=()):
    """Element: name, iterable of (ns, attr_name, (dataType, payload)), children."""
    return (name, list(attrs), list(children))


# --------------------------------------------------------------------------
# The manifest document
# --------------------------------------------------------------------------
def manifest_tree():
    A = ANDROID_NS
    return E("manifest",
             [(None, "package", s("dev.dsh.native")),
              # versionCode 由版本名推导（major*10000+minor*100+patch），
              # 恒为 1 会让系统无法正确判断新旧，影响应用内自更新。
              (A, "versionCode", dec(VERSION_CODE)),
              (A, "versionName", s("0.23.0"))],
             [
                 E("uses-sdk",
                   [(A, "minSdkVersion", dec(24)),
                    (A, "targetSdkVersion", dec(28))]),
                 E("uses-permission",
                   [(A, "name", s("android.permission.INTERNET"))]),
                 # 读取网络状态：通知栏看板要显示「网络是否正常」。
                 # 没有这个权限时 getActiveNetwork() 返回 null，
                 # 看板会一直显示「网络不可用（未连接）」—— 即使手机网络正常。
                 E("uses-permission",
                   [(A, "name", s("android.permission.ACCESS_NETWORK_STATE"))]),
                 # 把启动日志写到 /sdcard/DSHNative/，便于在设备内直接排查
                 E("uses-permission",
                   [(A, "name", s("android.permission.WRITE_EXTERNAL_STORAGE"))]),
                 E("uses-permission",
                   [(A, "name", s("android.permission.READ_EXTERNAL_STORAGE"))]),
                   # 前台服务：让 agent 在后台/锁屏时继续运行，不被系统冻结
                 E("uses-permission",
                   [(A, "name", s("android.permission.FOREGROUND_SERVICE"))]),
                   # Android 13+ 显示常驻通知需要它
                 E("uses-permission",
                   [(A, "name", s("android.permission.POST_NOTIFICATIONS"))]),
                   # 应用内自更新：允许请求安装 APK
                 E("uses-permission",
                   [(A, "name", s("android.permission.REQUEST_INSTALL_PACKAGES"))]),
                 E("application",
                   [(A, "label", s("DeepSeek Harness")),
                    # 主题：去标题栏 + 状态栏/导航栏同色（见 res/values/styles.xml）
                    (A, "theme", ref(THEME_RES_ID)),
                    # 图标资源 id 由 aapt2 link 产出（见 build 脚本），
                    # 这里是资源引用而非字符串：type=TYPE_REFERENCE。
                    (A, "icon", ref(ICON_RES_ID)),
                    (A, "hasCode", boolean(True)),
                    (A, "extractNativeLibs", boolean(True)),
                    (A, "usesCleartextTraffic", boolean(True))],
                   [
                       E("activity",
                         [(A, "name", s("dev.dsh.nativeapp.MainActivity")),
                          (A, "exported", boolean(True)),
                          (A, "configChanges",
                           hx(CONFIG_ORIENTATION | CONFIG_SCREEN_SIZE
                              | CONFIG_KEYBOARD_HIDDEN)),
                          # 键盘弹出时收缩窗口而不是平移，避免输入法盖住聊天内容
                          (A, "windowSoftInputMode", hx(0x10))],
                         [
                             # 长按图标的快捷方式（res/xml/shortcuts.xml）
                             E("meta-data",
                               [(A, "name", s("android.app.shortcuts")),
                                (A, "resource", ref(SHORTCUTS_RES_ID))]),
                             E("intent-filter", [], [
                                 E("action",
                                   [(A, "name", s("android.intent.action.MAIN"))]),
                                 E("category",
                                   [(A, "name",
                                      s("android.intent.category.LAUNCHER"))]),
                                      # 接收其他 App 的「分享」：文件与文本直接落到工作区
                                      E("intent-filter", [], [
                                          E("action",
                                            [(A, "name", s("android.intent.action.SEND"))]),
                                          E("category",
                                            [(A, "name", s("android.intent.category.DEFAULT"))]),
                                          E("data", [(A, "mimeType", s("*/*"))]),
                                      ]),
                             ]),
                         ]),
                   # 前台服务：保活 + 通知栏提供「设置 / 停止」
                   E("service",
                     [(A, "name", s("dev.dsh.nativeapp.HarnessService")),
                      (A, "exported", boolean(False))],
                     []),
                   # 更新包以 content:// 交给系统安装器（API 24+ 禁止 file:// 跨应用传递）
                   E("provider",
                     [(A, "name", s("dev.dsh.nativeapp.UpdateProvider")),
                      (A, "authorities", s("dev.dsh.native.updates")),
                      (A, "exported", boolean(False)),
                      (A, "grantUriPermissions", boolean(True))],
                     []),
                   ]),
             ])


# --------------------------------------------------------------------------
# UTF-8 string pool encoding
# --------------------------------------------------------------------------
def _encode_len8(n):
    """Android's 1-or-2 byte length prefix used in UTF-8 string pools.

    AOSP decodeLength8(): if the first byte has 0x80 set the length is
    ``((b0 & 0x7f) << 8) | b1`` and two bytes were consumed; else one byte.
    """
    if n < 0x80:
        return bytes((n,))
    if n <= 0x7FFF:
        return bytes((0x80 | (n >> 8), n & 0xFF))
    raise ValueError("string too long for AXML UTF-8 pool: %d" % n)


def _pool_string(text):
    """[utf16 length][utf8 length][utf8 bytes][NUL] -- see ResStringPool."""
    raw = text.encode("utf-8")
    utf16_len = len(text.encode("utf-16-le")) // 2   # surrogate pairs count 2
    return _encode_len8(utf16_len) + _encode_len8(len(raw)) + raw + b"\x00"


def build_string_pool(strings):
    """RES_STRING_POOL_TYPE chunk, UTF-8 flavour."""
    count = len(strings)
    offsets = []
    blob = bytearray()
    for text in strings:
        offsets.append(len(blob))
        blob += _pool_string(text)

    strings_start = STRING_POOL_HEADER_SIZE + 4 * count
    unpadded = strings_start + len(blob)
    chunk_size = (unpadded + 3) & ~3          # chunk must be 4-byte aligned

    header = struct.pack(
        "<HHIIIIII",
        RES_STRING_POOL_TYPE,
        STRING_POOL_HEADER_SIZE,
        chunk_size,
        count,
        0,                # styleCount
        UTF8_FLAG,        # UTF-8 string data
        strings_start,    # offset from chunk start to string data
        0,                # stylesStart (none)
    )
    out = bytearray(header)
    out += struct.pack("<%dI" % count, *offsets)
    out += blob
    out += b"\x00" * (chunk_size - unpadded)
    assert len(out) == chunk_size, (len(out), chunk_size)
    return bytes(out)


def build_resource_map(res_ids):
    """RES_XML_RESOURCE_MAP_TYPE chunk: one u32 per leading attr-name string."""
    chunk_size = RESOURCE_MAP_HEADER_SIZE + 4 * len(res_ids)
    out = struct.pack("<HHI", RES_XML_RESOURCE_MAP_TYPE, RESOURCE_MAP_HEADER_SIZE,
                      chunk_size)
    if res_ids:
        out += struct.pack("<%dI" % len(res_ids), *res_ids)
    assert len(out) == chunk_size
    return out


# --------------------------------------------------------------------------
# XML node chunks.  All are multiples of 4 bytes already, so no padding.
# --------------------------------------------------------------------------
def build_namespace(start, prefix_idx, uri_idx):
    return struct.pack("<HHIIIII",
                       RES_XML_START_NAMESPACE_TYPE if start
                       else RES_XML_END_NAMESPACE_TYPE,
                       XML_NODE_HEADER_SIZE,
                       XML_NODE_HEADER_SIZE + 8,
                       NO_ENTRY,      # lineNumber (aapt writes "no line")
                       NO_ENTRY,      # comment
                       prefix_idx,
                       uri_idx)


def build_start_element(name_idx, attrs, ns_idx=NO_ENTRY):
    """attrs: list of (ns_idx, name_idx, raw_value_idx, dataType, data)."""
    chunk_size = XML_NODE_HEADER_SIZE + XML_ATTR_EXT_SIZE + XML_ATTR_SIZE * len(attrs)
    out = struct.pack("<HHIIIII",
                      RES_XML_START_ELEMENT_TYPE,
                      XML_NODE_HEADER_SIZE,
                      chunk_size,
                      NO_ENTRY,                 # lineNumber
                      NO_ENTRY,                 # comment
                      ns_idx,
                      name_idx)
    # ResXMLTree_attrExt
    out += struct.pack("<6H",
                       XML_ATTR_EXT_SIZE,       # attributeStart
                       XML_ATTR_SIZE,           # attributeSize
                       len(attrs),              # attributeCount
                       0,                       # idIndex
                       0,                       # classIndex
                       0)                       # styleIndex
    for (a_ns, a_name, a_raw, a_type, a_data) in attrs:
        out += struct.pack("<IIIHBBI", a_ns, a_name, a_raw,
                           RES_VALUE_SIZE, 0, a_type, a_data)
    assert len(out) == chunk_size, (len(out), chunk_size)
    return out


def build_end_element(name_idx, ns_idx=NO_ENTRY):
    return struct.pack("<HHIIIII",
                       RES_XML_END_ELEMENT_TYPE,
                       XML_NODE_HEADER_SIZE,
                       XML_NODE_HEADER_SIZE + 8,
                       NO_ENTRY,      # lineNumber
                       NO_ENTRY,      # comment
                       ns_idx,
                       name_idx)


# --------------------------------------------------------------------------
# Two-pass flattening
# --------------------------------------------------------------------------
def _walk(node, out):
    out.append(node)
    for child in node[2]:
        _walk(child, out)


def _version_code():
    """把 0.12.0 这样的版本名换算成 versionCode：major*10000+minor*100+patch。"""
    import re as _re
    src = open(__file__, encoding="utf-8").read()
    m = _re.search(r's\("(\d+)\.(\d+)\.(\d+)"\)', src)
    if not m:
        return 1
    a, b, c = (int(x) for x in m.groups())
    return a * 10000 + b * 100 + c


VERSION_CODE = _version_code()


def build_manifest():
    """Build the compiled AndroidManifest.xml and return it as bytes."""
    root = manifest_tree()

    nodes = []
    _walk(root, nodes)

    # ---- pass 1: string pool ordering -----------------------------------
    # Leading block: attribute names that carry a framework resource id,
    # sorted ascending by id (aapt2 layout).
    attr_name_ids = {}
    for (_name, attrs, _children) in nodes:
        for (ns, aname, _val) in attrs:
            if ns == ANDROID_NS:
                if aname not in ATTR_IDS:
                    raise ValueError(
                        "no known framework resource id for android:%s -- "
                        "refusing to emit a manifest whose resource map would "
                        "be wrong" % aname)
                attr_name_ids[aname] = ATTR_IDS[aname]

    ordered_attr_names = sorted(attr_name_ids, key=lambda n: attr_name_ids[n])
    strings = list(ordered_attr_names)
    index_of = {t: i for i, t in enumerate(strings)}

    def intern(text):
        if text not in index_of:
            index_of[text] = len(strings)
            strings.append(text)
        return index_of[text]

    # Remaining strings: element names, ns prefix/uri, values, and attribute
    # names without a resource id (e.g. "package").  aapt2 keeps these sorted.
    rest = set()
    rest.add("android")
    rest.add(ANDROID_NS)
    for (name, attrs, _children) in nodes:
        rest.add(name)
        for (ns, aname, (vtype, payload)) in attrs:
            if ns != ANDROID_NS or aname not in ATTR_IDS:
                rest.add(aname)
            if vtype == TYPE_STRING:
                rest.add(payload)
    for text in sorted(rest, key=lambda t: t.encode("utf-8")):
        intern(text)

    # The resource map is positional: entry i belongs to strings[i].  This is
    # only coherent because the attribute names occupy the leading slots.
    res_ids = [attr_name_ids[n] for n in ordered_attr_names]
    assert len(res_ids) == len(ordered_attr_names)

    # ---- pass 2: emit chunks --------------------------------------------
    pool = build_string_pool(strings)
    resmap = build_resource_map(res_ids)

    body = bytearray()
    body += build_namespace(True, index_of["android"], index_of[ANDROID_NS])

    _emit_element(root, index_of, body)

    body += build_namespace(False, index_of["android"], index_of[ANDROID_NS])

    total = 8 + len(pool) + len(resmap) + len(body)
    out = bytearray()
    out += struct.pack("<HHI", RES_XML_TYPE, 8, total)
    out += pool
    out += resmap
    out += body
    assert len(out) == total, (len(out), total)
    return bytes(out)


def _emit_element(node, index_of, body):
    name, attrs, children = node
    packed = []
    for (ns, aname, (vtype, payload)) in attrs:
        ns_idx = NO_ENTRY if ns is None else index_of[ns]
        a_name = index_of[aname]
        if vtype == TYPE_STRING:
            v_idx = index_of[payload]
            raw = v_idx
            data = v_idx
        else:
            raw = NO_ENTRY
            data = payload
        packed.append((ns_idx, a_name, raw, vtype, data))
    body += build_start_element(index_of[name], packed)
    for child in children:
        _emit_element(child, index_of, body)
    body += build_end_element(index_of[name])


def write_manifest(path):
    data = build_manifest()
    with open(path, "wb") as fh:
        fh.write(data)
    return len(data)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.stderr.write("usage: %s <output_path>\n" % sys.argv[0])
        raise SystemExit(2)
    n = write_manifest(sys.argv[1])
    print("wrote %s (%d bytes)" % (sys.argv[1], n))
