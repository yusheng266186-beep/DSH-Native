#!/usr/bin/env python3
"""用 Pillow 完成 sharp 在 Android 上所需的图像操作。

DSH 只用到很窄的一组 sharp API：

    sharp(data, {failOn, limitInputPixels}).metadata()
    sharp(data, {...}).rotate().toColourspace('srgb')
        .resize({width, height, fit: 'inside', withoutEnlargement: true})
        .webp({quality}) | .jpeg({quality})  →  .toBuffer({resolveWithObject})

因此不需要编译原生 sharp（它依赖 glibc 链接的 libvips，bionic 上无法 dlopen），
用 Pillow 实现同样语义即可。本脚本由 sharp-android.js 以子进程方式调用，
通过 stdin 收 JSON、stdout 回 JSON。
"""

import base64
import io
import json
import sys

from PIL import Image, ImageOps

# 大图保护：DSH 传 limitInputPixels: false，但手机上仍要防内存爆掉
Image.MAX_IMAGE_PIXELS = None


def do_metadata(data):
    im = Image.open(io.BytesIO(data))
    fmt = (im.format or "unknown").lower()
    if fmt == "jpg":
        fmt = "jpeg"
    has_alpha = im.mode in ("RGBA", "LA", "PA") or "transparency" in im.info
    return {
        "format": fmt,
        "width": im.width,
        "height": im.height,
        "hasAlpha": has_alpha,
        "channels": len(im.getbands()),
        "space": "srgb",
        "depth": "uchar",
        "size": len(data),
    }


def do_process(data, ops, fmt, quality):
    im = Image.open(io.BytesIO(data))
    for op in ops or []:
        name = op[0]
        if name == "rotate":
            # sharp 的 rotate() 无参时按 EXIF 方向自动纠正
            im = ImageOps.exif_transpose(im)
        elif name == "colourspace":
            # Pillow 全程按 sRGB 语义处理，无需显式转换
            pass
        elif name == "resize":
            p = op[1] or {}
            w = p.get("width")
            h = p.get("height")
            if not w and not h:
                continue
            fit = p.get("fit") or "cover"
            no_enlarge = bool(p.get("withoutEnlargement"))
            if fit == "inside":
                # thumbnail() 本身就是「等比缩到不超过给定尺寸」，且只缩不放
                im.thumbnail((w or im.width, h or im.height), Image.LANCZOS)
            elif fit == "fill":
                im = im.resize((w or im.width, h or im.height), Image.LANCZOS)
            else:
                # cover / contain 等：按目标框等比缩放
                tw, th = w or im.width, h or im.height
                ratio = min(tw / im.width, th / im.height)
                if no_enlarge:
                    ratio = min(ratio, 1.0)
                im = im.resize((max(1, int(im.width * ratio)),
                                max(1, int(im.height * ratio))), Image.LANCZOS)
        elif name == "raw":
            # 供 DSH 做「能否完整解码」的校验；这里主动 load 一次
            im.load()

    out = io.BytesIO()
    f = (fmt or "png").lower()
    if f in ("jpeg", "jpg"):
        im.convert("RGB").save(out, "JPEG", quality=int(quality or 80), optimize=True)
    elif f == "webp":
        im.save(out, "WEBP", quality=int(quality or 80), method=0)
    else:
        if im.mode == "P":
            im = im.convert("RGBA" if "transparency" in im.info else "RGB")
        im.save(out, "PNG", optimize=True)

    blob = out.getvalue()
    return {
        "data": base64.b64encode(blob).decode("ascii"),
        "width": im.width,
        "height": im.height,
        "format": f,
        "size": len(blob),
    }


def main():
    try:
        req = json.loads(sys.stdin.read())
        data = base64.b64decode(req["data"])
        op = req.get("op")
        if op == "metadata":
            result = do_metadata(data)
        elif op == "process":
            result = do_process(data, req.get("ops"), req.get("format"),
                                req.get("quality"))
        else:
            raise ValueError("unknown op: %r" % (op,))
        sys.stdout.write(json.dumps(result))
    except Exception as exc:  # 让 JS 侧拿到可读错误
        sys.stdout.write(json.dumps({
            "_error": "%s: %s" % (type(exc).__name__, exc),
        }))
    sys.stdout.flush()


if __name__ == "__main__":
    main()
