#!/usr/bin/env python3
"""生成 DSH Native 图标（纯标准库，4x 超采样抗锯齿）。"""
import zlib, struct, os, math

def chunk(t, d):
    return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)

def write_png(path, w, h, rgba):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw += rgba[y*w*4:(y+1)*w*4]
    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')
    open(path, 'wb').write(png)

def lerp(a, b, t): return a + (b - a) * t

def render(size, ss=4):
    """渲染 size x size 图标，ss 为超采样倍数。"""
    S = size * ss
    buf = [[(0.0, 0.0, 0.0, 0.0)] * S for _ in range(S)]

    R = S * 0.235                      # 圆角半径
    # 渐变端点（靛蓝 → 紫罗兰）
    C1 = (79, 70, 229)
    C2 = (139, 92, 246)

    for y in range(S):
        for x in range(S):
            # 圆角矩形内部判定
            dx = dy = 0.0
            if x < R: dx = R - x
            elif x > S - 1 - R: dx = x - (S - 1 - R)
            if y < R: dy = R - y
            elif y > S - 1 - R: dy = y - (S - 1 - R)
            if dx*dx + dy*dy > R*R:
                continue
            t = y / (S - 1)
            buf[y][x] = (lerp(C1[0], C2[0], t), lerp(C1[1], C2[1], t), lerp(C1[2], C2[2], t), 255.0)

    # 画白色终端提示符  >_
    W = 255.0
    th = S * 0.075                     # 笔画粗细
    cx, cy = S * 0.34, S * 0.50        # 尖角位置
    arm = S * 0.155                    # 斜边长度

    def put(x, y, a=1.0):
        if 0 <= x < S and 0 <= y < S:
            r, g, b, al = buf[y][x]
            na = min(255.0, al + 255.0 * a) if al > 0 else 0
            if al <= 0: return
            buf[y][x] = (lerp(r, W, a), lerp(g, W, a), lerp(b, W, a), al)

    def thick_line(x0, y0, x1, y1, half):
        steps = int(max(abs(x1-x0), abs(y1-y0)) * 1.6) + 2
        for i in range(steps + 1):
            t = i / steps
            px, py = lerp(x0, x1, t), lerp(y0, y1, t)
            x_lo, x_hi = int(px - half) - 1, int(px + half) + 1
            y_lo, y_hi = int(py - half) - 1, int(py + half) + 1
            for yy in range(max(0, y_lo), min(S, y_hi + 1)):
                for xx in range(max(0, x_lo), min(S, x_hi + 1)):
                    d = math.hypot(xx - px, yy - py)
                    if d <= half:
                        put(xx, yy, 1.0)
                    elif d <= half + 1.0:
                        put(xx, yy, half + 1.0 - d)

    half = th / 2.0
    thick_line(cx - arm*0.5, cy - arm, cx + arm*0.5, cy, half)   # 上斜
    thick_line(cx + arm*0.5, cy, cx - arm*0.5, cy + arm, half)   # 下斜
    # 下划线
    ux0, ux1 = S * 0.56, S * 0.76
    uy = cy + arm
    thick_line(ux0, uy, ux1, uy, half)

    # 降采样
    out = bytearray()
    for y in range(size):
        for x in range(size):
            r = g = b = a = 0.0
            for sy in range(ss):
                for sx in range(ss):
                    pr, pg, pb, pa = buf[y*ss+sy][x*ss+sx]
                    w = pa / 255.0
                    r += pr * w; g += pg * w; b += pb * w; a += pa
            n = ss * ss
            # 先各自取平均，再反预乘（此前漏了 /n，导致颜色被 min(255,…) 截断成全白）
            r /= n; g /= n; b /= n; a /= n
            if a > 0:
                inv = 255.0 / a
                R = int(min(255, r * inv)); G = int(min(255, g * inv)); B = int(min(255, b * inv))
            else:
                R = G = B = 0
            out += bytes([R, G, B, int(a)])
    return bytes(out)

DENSITIES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
base = os.path.dirname(os.path.abspath(__file__))
for name, size in DENSITIES.items():
    d = os.path.join(base, 'res', f'mipmap-{name}')
    os.makedirs(d, exist_ok=True)
    p = os.path.join(d, 'ic_launcher.png')
    write_png(p, size, size, render(size))
    print(f"  {name:8} {size}x{size}  {os.path.getsize(p)} 字节")
