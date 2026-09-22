#!/usr/bin/env python3
"""生成 DeepSeek Harness 应用图标。

素材：whale.png —— DeepSeek 官方鲸鱼标（取自官网 favicon，品牌蓝 #4D6AFE）。
风格参照 DeepSeek 官方图标：浅色底 + 蓝色鲸鱼。

产出：
  res/mipmap-<density>/ic_launcher.png            传统图标（圆角矩形底）
  res/mipmap-<density>/ic_launcher_foreground.png 自适应前景（透明底 + 鲸鱼）
  res/mipmap-anydpi-v26/ic_launcher.xml           自适应图标声明
  res/drawable/ic_launcher_background.xml         背景（浅色渐变）

纯标准库：自带 PNG 解码、面积平均缩放（预乘 alpha）、PNG 编码。
"""

import math
import os
import struct
import zlib

DENSITIES = {
    'mdpi': (48, 108),
    'hdpi': (72, 162),
    'xhdpi': (96, 216),
    'xxhdpi': (144, 324),
    'xxxhdpi': (192, 432),
}

# 背景：极浅的蓝白渐变，保持 DeepSeek 官方那种干净感
BG_TOP = (255, 255, 255)
BG_BOTTOM = (238, 242, 255)


# ---------------------------------------------------------------- PNG 编解码
def _chunk(tag, data):
    return (struct.pack('>I', len(data)) + tag + data
            + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff))


def decode_png(path):
    """解码 8 位 RGBA PNG，返回 (w, h, bytearray RGBA)。"""
    d = open(path, 'rb').read()
    assert d[:8] == b'\x89PNG\r\n\x1a\n', 'not a png'
    pos, idat = 8, b''
    w = h = bit = ctype = None
    while pos < len(d):
        ln = struct.unpack('>I', d[pos:pos + 4])[0]
        tag = d[pos + 4:pos + 8]
        data = d[pos + 8:pos + 8 + ln]
        if tag == b'IHDR':
            w, h, bit, ctype = struct.unpack('>IIBB', data[:10])
        elif tag == b'IDAT':
            idat += data
        pos += 12 + ln
    assert bit == 8 and ctype == 6, f'仅支持 8 位 RGBA，实际 bit={bit} ctype={ctype}'
    raw = zlib.decompress(idat)
    stride = w * 4
    out = bytearray(w * h * 4)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if f == 1:
            for i in range(4, stride):
                line[i] = (line[i] + line[i - 4]) & 0xFF
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                a = line[i - 4] if i >= 4 else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = line[i - 4] if i >= 4 else 0
                b = prev[i]
                c = prev[i - 4] if i >= 4 else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, out


def write_png(path, w, h, rgba):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw += rgba[y * w * 4:(y + 1) * w * 4]
    png = b'\x89PNG\r\n\x1a\n'
    png += _chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    png += _chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += _chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(png)


# ---------------------------------------------------------------- 缩放与合成
def sample(src, sw, sh, fx, fy):
    """双线性采样源图（fx/fy 为像素中心坐标，可越界 → 透明）。"""
    x0, y0 = int(math.floor(fx)), int(math.floor(fy))
    out = [0.0, 0.0, 0.0, 0.0]
    for dy in (0, 1):
        for dx in (0, 1):
            x, y = x0 + dx, y0 + dy
            wgt = (1 - abs(fx - x)) * (1 - abs(fy - y))
            if wgt <= 0 or x < 0 or y < 0 or x >= sw or y >= sh:
                continue
            o = (y * sw + x) * 4
            a = src[o + 3] / 255.0
            out[0] += src[o] * a * wgt
            out[1] += src[o + 1] * a * wgt
            out[2] += src[o + 2] * a * wgt
            out[3] += src[o + 3] * wgt
    return out


def place_whale(dst, size, src, sw, sh, ratio, dx_shift=0.0, dy_shift=0.0):
    """把鲸鱼按比例缩放后居中合成到 dst（RGBA，size×size）。

    采用「超采样 × 面积平均」：每个目标像素取 k×k 个样本再平均，
    因此缩小到 48px 也不会出现锯齿或丢细节。
    """
    box = size * ratio
    scale = box / max(sw, sh)
    ox = (size - sw * scale) / 2.0 + size * dx_shift
    oy = (size - sh * scale) / 2.0 + size * dy_shift
    k = 3 if size >= 96 else 4        # 每像素采样数
    inv = 1.0 / (k * k)
    for y in range(size):
        for x in range(size):
            r = g = b = a = 0.0
            for sy in range(k):
                for sx in range(k):
                    fx = (x + (sx + 0.5) / k - ox) / scale
                    fy = (y + (sy + 0.5) / k - oy) / scale
                    if fx < 0 or fy < 0 or fx >= sw or fy >= sh:
                        continue
                    s = sample(src, sw, sh, fx, fy)
                    r += s[0]; g += s[1]; b += s[2]; a += s[3]
            if a <= 0:
                continue
            # 反预乘得到真实颜色
            aa = a * inv
            alpha = aa / 255.0
            o = (y * size + x) * 4
            sa = min(1.0, alpha)
            br, bg_, bb = dst[o], dst[o + 1], dst[o + 2]
            dst[o] = int(r * inv / alpha)
            dst[o + 1] = int(g * inv / alpha)
            dst[o + 2] = int(b * inv / alpha)
            dst[o + 3] = max(dst[o + 3], int(255 * sa))


def base_canvas(size, rounded):
    """浅色渐变底；rounded=True 画圆角矩形，否则整块铺满。"""
    buf = bytearray(size * size * 4)
    R = size * 0.235
    for y in range(size):
        t = y / max(1, size - 1)
        r = int(BG_TOP[0] + (BG_BOTTOM[0] - BG_TOP[0]) * t)
        g = int(BG_TOP[1] + (BG_BOTTOM[1] - BG_TOP[1]) * t)
        b = int(BG_TOP[2] + (BG_BOTTOM[2] - BG_TOP[2]) * t)
        if rounded:
            dy = 0.0
            if y < R:
                dy = R - y
            elif y > size - 1 - R:
                dy = y - (size - 1 - R)
            span = math.sqrt(max(0.0, R * R - dy * dy)) if dy > 0 else R
            x0 = max(0, int(R - span))
            x1 = min(size, int(size - R + span))
        else:
            x0, x1 = 0, size
        if x1 <= x0:
            continue
        row = bytes([r, g, b, 255]) * (x1 - x0)
        off = (y * size + x0) * 4
        buf[off:off + len(row)] = row
    return buf


def main():
    base = os.path.dirname(os.path.abspath(__file__))
    sw, sh, whale = decode_png(os.path.join(base, 'whale.png'))
    opaque = sum(1 for i in range(3, len(whale), 4) if whale[i] > 128)
    print(f"  素材 whale.png {sw}×{sh}，不透明 {100*opaque/(sw*sh):.1f}%")

    for name, (legacy, adaptive) in DENSITIES.items():
        d = os.path.join(base, 'res', 'mipmap-' + name)
        os.makedirs(d, exist_ok=True)

        # 传统图标：圆角浅底 + 鲸鱼（占 78%）
        c1 = base_canvas(legacy, rounded=True)
        place_whale(c1, legacy, whale, sw, sh, 0.78)
        write_png(os.path.join(d, 'ic_launcher.png'), legacy, legacy, c1)

        # 自适应前景：透明底 + 鲸鱼（占 60%，收在安全区内）
        c2 = bytearray(adaptive * adaptive * 4)
        place_whale(c2, adaptive, whale, sw, sh, 0.58)
        write_png(os.path.join(d, 'ic_launcher_foreground.png'),
                  adaptive, adaptive, c2)

        print(f"  {name:8} legacy {legacy:>3}px  foreground {adaptive:>3}px")

    anydpi = os.path.join(base, 'res', 'mipmap-anydpi-v26')
    os.makedirs(anydpi, exist_ok=True)
    with open(os.path.join(anydpi, 'ic_launcher.xml'), 'w') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/ic_launcher_background"/>\n'
                '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
                '</adaptive-icon>\n')

    dr = os.path.join(base, 'res', 'drawable')
    os.makedirs(dr, exist_ok=True)
    with open(os.path.join(dr, 'ic_launcher_background.xml'), 'w') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<shape xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    android:shape="rectangle">\n'
                '    <gradient\n'
                '        android:startColor="#FFFFFFFF"\n'
                '        android:endColor="#FFEEF2FF"\n'
                '        android:angle="270"/>\n'
                '</shape>\n')
    print("  自适应图标 XML + 浅色背景渐变已生成")


if __name__ == '__main__':
    main()
