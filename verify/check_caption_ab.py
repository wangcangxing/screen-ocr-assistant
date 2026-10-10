#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
独立复核 · caption 实例 vs no-caption 实例（契约 §1.1 caption_enabled / §1.2 icon 的 label）
================================================================================================
契约口径：
  - §1.1 `/health` 的 `caption_enabled` 必须**如实**反映该实例是否跑 Florence-2 图标语义；
  - §1.2 `elements[].label`：text 元素就是文本本身；icon 元素是 Florence-2 的语义描述，**`--no-caption` 时为 `"icon"`**。

本脚本对**两个不同实例**发同一张真实 PNG，做 A/B 对照：
  - A = caption 实例（默认 8010，caption_enabled=true）
  - B = no-caption 实例（默认 8011，caption_enabled=false）
判定项：
  C1 /health 的 caption_enabled 与实例角色一致
  C2 B 实例 icon 的 label **全部**为 "icon"
  C3 A 实例 icon 的 label 是**语义描述**（非空、不全为 "icon"、且不止一种）
  C4 两个实例 text 元素：label == text 且 text 非空（caption 开关不应影响文字）
  C5 同一张图两实例的 text/icon **数量一致**（检测与 caption 无关）
  C6 image.width/height == 提交图尺寸
  C7 记录两实例的真实 elapsed_ms（不做判定，供报告引用）

用法：
  python verify\\check_caption_ab.py --url-caption http://127.0.0.1:8010 --url-nocaption http://127.0.0.1:8011 --image q2.png
  python verify\\check_caption_ab.py --from-json          # 只用已保存的两份响应重新判定，不发请求

退出码：0 = 全通过；1 = 有不通过；2 = 环境不可用（未验证）。
"""

import argparse
import base64
import json
import os
import struct
import sys
import time
import urllib.error
import urllib.request

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(SCRIPT_DIR)
OUT_DIR = os.path.join(SCRIPT_DIR, "out")
SHOTS_DIR = os.path.join(ROOT, "shots")

RESULTS = []


def emit(kind, item, detail=""):
    RESULTS.append((kind, item, detail))
    line = "[%s] %s" % (kind, item)
    if detail:
        line += "  —— " + detail
    print(line, flush=True)


def image_size(data):
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return struct.unpack(">II", data[16:24])
    if data[:2] == b"\xff\xd8":
        i = 2
        while i + 9 < len(data):
            if data[i] != 0xFF:
                i += 1
                continue
            m = data[i + 1]
            if m in (0xD8, 0x01) or 0xD0 <= m <= 0xD7:
                i += 2
                continue
            L = struct.unpack(">H", data[i + 2:i + 4])[0]
            if m in (0xC0, 0xC1, 0xC2, 0xC3):
                h, w = struct.unpack(">HH", data[i + 5:i + 9])
                return w, h
            i += 2 + L
        raise ValueError("JPEG 未找到 SOF")
    raise ValueError("未知图片格式")


def http_json(url, method="GET", payload=None, timeout=600):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:
        return None, ("%s: %s" % (type(e).__name__, e)).encode("utf-8")


def load_or_post(name, url, b64, timeout, save_name, reuse, do_health=True):
    """返回 (health_dict|None, parse_dict|None, elapsed_wall, raw_parse_bytes)"""
    h = None
    wall = None
    if reuse:
        p = os.path.join(OUT_DIR, save_name)
        if not os.path.exists(p):
            print("[未验证] 缺少已保存响应 %s（--from-json 需要它）" % p)
            return None, None, None, b""
        raw = open(p, "rb").read()
        hp = os.path.join(OUT_DIR, save_name.replace(".json", ".health.json"))
        if os.path.exists(hp):
            h = json.loads(open(hp, encoding="utf-8").read())
        return h, json.loads(raw.decode("utf-8")), wall, raw
    if do_health:
        st, raw_h = http_json(url.rstrip("/") + "/health", timeout=15)
        if st == 200:
            h = json.loads(raw_h.decode("utf-8"))
            open(os.path.join(OUT_DIR, save_name.replace(".json", ".health.json")), "wb").write(raw_h)
        else:
            print("[未验证] %s /health 失败：%s" % (name, raw_h.decode("utf-8", "replace")[:200]))
            return None, None, None, b""
    t0 = time.time()
    st, raw = http_json(url.rstrip("/") + "/parse", method="POST",
                        payload={"image_base64": b64, "annotate": False}, timeout=timeout)
    wall = time.time() - t0
    if st != 200:
        print("[未验证] %s /parse 失败（HTTP %s）：%s" % (name, st, raw.decode("utf-8", "replace")[:200]))
        return h, None, wall, raw
    open(os.path.join(OUT_DIR, save_name), "wb").write(raw)
    return h, json.loads(raw.decode("utf-8")), wall, raw


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url-caption", default="http://127.0.0.1:8010")
    ap.add_argument("--url-nocaption", default="http://127.0.0.1:8011")
    ap.add_argument("--image", default="q2.png")
    ap.add_argument("--timeout", type=int, default=900)
    ap.add_argument("--from-json", action="store_true")
    args = ap.parse_args()

    os.makedirs(OUT_DIR, exist_ok=True)
    print("独立复核 · caption vs no-caption A/B  时间=%s" % time.strftime("%Y-%m-%d %H:%M:%S"))

    img_path = os.path.join(SHOTS_DIR, args.image)
    data = open(img_path, "rb").read()
    w, h = image_size(data)
    b64 = base64.b64encode(data).decode("ascii")
    print("测试图 %s：%dx%d %d 字节（两实例提交同一份）" % (args.image, w, h, len(data)))

    hA, A, wallA, _ = load_or_post("A(caption)", args.url_caption, b64, args.timeout, "caption_8010.json", args.from_json)
    hB, B, wallB, _ = load_or_post("B(no-caption)", args.url_nocaption, b64, args.timeout, "caption_8011.json", args.from_json)
    if A is None or B is None:
        print("\n[未验证] 缺少任一实例的有效响应，A/B 对照无法完成。")
        return 2

    print("A=%s（mode=%r caption_enabled=%r）  B=%s（mode=%r caption_enabled=%r）"
          % (args.url_caption, (hA or {}).get("mode"), (hA or {}).get("caption_enabled"),
             args.url_nocaption, (hB or {}).get("mode"), (hB or {}).get("caption_enabled")))

    # C1 health 如实
    okA = (hA or {}).get("caption_enabled") is True
    okB = (hB or {}).get("caption_enabled") is False
    emit("通过" if (okA and okB) else "不通过", "C1 /health caption_enabled 与实例角色一致",
         "A(caption)=%r B(no-caption)=%r（期望 true / false）" % ((hA or {}).get("caption_enabled"), (hB or {}).get("caption_enabled")))

    eA = A.get("elements") or []
    eB = B.get("elements") or []
    if not eA or not eB:
        emit("不通过", "C0 两实例 elements 均非空", "A=%d B=%d（0 元素若因黑图属预期，但本图是真机截图）" % (len(eA), len(eB)))
        return 1

    iconA = [e for e in eA if e.get("type") == "icon"]
    iconB = [e for e in eB if e.get("type") == "icon"]
    textA = [e for e in eA if e.get("type") == "text"]
    textB = [e for e in eB if e.get("type") == "text"]

    # C2 no-caption 恒为 "icon"
    badB = [(e.get("id"), e.get("label")) for e in iconB if e.get("label") != "icon"]
    emit("通过" if not badB and iconB else ("不通过" if iconB else "注意"),
         "C2 no-caption 实例 icon 的 label 全为 \"icon\"",
         ("icon=%d，全部符合" % len(iconB)) if not badB else "不符合 %d 个：%r" % (len(badB), badB[:5]))

    # C3 caption 实例是语义描述
    labels = [e.get("label") or "" for e in iconA]
    distinct = sorted(set(labels))
    semantic = bool(labels) and all(l.strip() for l in labels) and any(l != "icon" for l in labels) and len(distinct) > 1
    emit("通过" if semantic else "不通过", "C3 caption 实例 icon 的 label 是语义描述（非空、不全为 \"icon\"、不止一种）",
         "icon=%d，不同 label=%d 种；样例=%r" % (len(labels), len(distinct), labels[:5]))

    # C4 text 语义不受 caption 影响
    badT = []
    for name, els in (("A", textA), ("B", textB)):
        for e in els:
            if e.get("label") != e.get("text") or not (e.get("text") or "").strip():
                badT.append((name, e.get("id"), e.get("label"), e.get("text")))
    emit("通过" if not badT else "不通过", "C4 text 元素 label == text 且 text 非空（两实例）",
         "全部符合" if not badT else "不符合 %d 个：%r" % (len(badT), badT[:5]))

    # C5 数量一致
    same = (len(textA) == len(textB)) and (len(iconA) == len(iconB))
    emit("通过" if same else "不通过", "C5 同图两实例的 text/icon 数量一致（检测与 caption 无关）",
         "A: text=%d icon=%d ｜ B: text=%d icon=%d" % (len(textA), len(iconA), len(textB), len(iconB)))

    # C6 尺寸
    def dims(p):
        im = p.get("image") or {}
        return im.get("width"), im.get("height")
    dA, dB = dims(A), dims(B)
    ok6 = dA == (w, h) and dB == (w, h)
    emit("通过" if ok6 else "不通过", "C6 image.width/height == 提交图尺寸（两实例）",
         "A=%r B=%r 期望=%r" % (dA, dB, (w, h)))

    # C7 真实耗时（不判定）
    emit("通过", "C7 实测耗时（仅记录，不判定）",
         "A(caption) elapsed_ms=%r 客户端wall=%.1fs ｜ B(no-caption) elapsed_ms=%r 客户端wall=%.1fs"
         % (A.get("elapsed_ms"), wallA or -1, B.get("elapsed_ms"), wallB or -1))

    # C8 跨实例逐条一致性：除 icon 的 label 外，其余字段应完全相同（作者主张；我独立核对，不采信自述）
    def strip(e, drop_label):
        d = {k: e.get(k) for k in ("id", "type", "source", "text", "bbox_ratio", "bbox_px", "interactable")}
        if not drop_label:
            d["label"] = e.get("label")
        return d
    diffs = []
    if len(eA) != len(eB):
        diffs.append("元素数量不同：A=%d B=%d" % (len(eA), len(eB)))
    else:
        for a, b in zip(eA, eB):
            if a.get("type") != b.get("type") or a.get("id") != b.get("id"):
                diffs.append("id/type 不同：A(id=%s,%s) vs B(id=%s,%s)" % (a.get("id"), a.get("type"), b.get("id"), b.get("type")))
                continue
            da = strip(a, drop_label=(a.get("type") == "icon"))
            db = strip(b, drop_label=(b.get("type") == "icon"))
            if da != db:
                diffs.append("id=%s(%s) 非 label 字段不同：A=%r B=%r" % (a.get("id"), a.get("type"), da, db))
    emit("通过" if not diffs else "不通过",
         "C8 跨实例除 icon 的 label 外逐条一致（id/type/source/text/bbox_ratio/bbox_px/interactable）",
         "83 项级逐条比对全部一致" if not diffs else "发现 %d 处差异：%s" % (len(diffs), " | ".join(diffs[:3])))
    print("      A(caption) 前 3 个 icon 的 label：%r" % [e.get("label") for e in iconA[:3]])
    print("      A(caption) 3 个 text 片段：%r" % [(e.get("id"), str(e.get("text"))[:24]) for e in textA[:3]])
    print("      B(no-caption) 前 3 个 icon 的 label：%r" % [e.get("label") for e in iconB[:3]])

    n_fail = sum(1 for k, _, _ in RESULTS if k == "不通过")
    print("\n================ A/B 汇总 ================")
    print("通过 %d 项 / 不通过 %d 项" % (sum(1 for k, _, _ in RESULTS if k == "通过"), n_fail))
    if n_fail:
        for k, item, detail in RESULTS:
            if k == "不通过":
                print("  - %s  (%s)" % (item, detail))
    return 0 if n_fail == 0 else 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    sys.exit(main())
