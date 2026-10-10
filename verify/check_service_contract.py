#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
独立验证脚本 · 解析服务 HTTP 契约（对抗性）
=============================================
口径来源（唯一）：docs\\接口约定-SoM与解析服务.md（冻结契约 v1）§1.1 GET /health、§1.2 POST /parse。
本脚本只做「按契约逐字段核对」，不读实现代码、不做实现侧推断；结论只认本脚本实际跑出来的输出。

用法（工作区根 D:\\program\\screen-ocr-assistant-omniparser）：
    python verify\\check_service_contract.py                # 默认 http://127.0.0.1:8010
    python verify\\check_service_contract.py --base-url http://127.0.0.1:8010
    python verify\\check_service_contract.py --selftest     # 离线自检：不联网，只验证脚本自身的解析/生成逻辑
    python verify\\check_service_contract.py --skip-heavy   # 跳过超大图(413)与并发用例

输出：逐条 [通过]/[不通过]/[注意]，末尾汇总；原始响应落 verify\\out\\。
退出码：0=全部通过（无 [不通过]）；1=有 [不通过]；2=环境不可用（服务未启动，结论为“未验证”）。
"""

import argparse
import base64
import json
import os
import struct
import sys
import threading
import time
import traceback
import urllib.error
import urllib.request
import zlib

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(SCRIPT_DIR)
OUT_DIR = os.path.join(SCRIPT_DIR, "out")
SHOTS_DIR = os.path.join(ROOT, "shots")

LABEL = ""        # 本次采证标注（端口/模式），由 --label 传入
RESULTS = []      # (kind, item, detail)  kind in {"通过","不通过","注意","未验证"}
NOTES = []
MODE = "unknown"  # real | mock | unknown，来自 /health
ROW_BUCKET_PX = 30  # 「同一行」容差：契约 §2.1 的 30px（服务端实现同样取 30px，见 omniparser-service/server.py ROW_BUCKET_PX）


def emit(kind, item, detail=""):
    RESULTS.append((kind, item, detail))
    line = "[%s] %s" % (kind, item)
    if detail:
        line += "  —— " + detail
    print(line, flush=True)


def note(text):
    NOTES.append(text)
    print("      · " + text, flush=True)


# ---------------------------------------------------------------- HTTP ----

def http_call(method, url, body=None, content_type="application/json", timeout=120):
    """返回 (status, raw_bytes, headers_dict)。网络层异常返回 (None, b'', {'error': ...})，不抛出。"""
    req = urllib.request.Request(url, data=body, method=method)
    if body is not None:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read(), dict(resp.headers)
    except urllib.error.HTTPError as e:
        try:
            raw = e.read()
        except Exception:
            raw = b""
        return e.code, raw, dict(e.headers or {})
    except Exception as e:  # 连接被拒 / 超时 / 服务端提前断连
        return None, b"", {"error": "%s: %s" % (type(e).__name__, e)}


def parse_json(raw):
    try:
        return json.loads(raw.decode("utf-8")), None
    except Exception as e:
        return None, "%s: %s" % (type(e).__name__, e)


def post_parse(base_url, payload_obj=None, raw_body=None, timeout=600, headers=None):
    if raw_body is None:
        raw_body = json.dumps(payload_obj).encode("utf-8")
    req = urllib.request.Request(base_url.rstrip("/") + "/parse", data=raw_body, method="POST")
    req.add_header("Content-Type", headers or "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read(), dict(resp.headers)
    except urllib.error.HTTPError as e:
        try:
            raw = e.read()
        except Exception:
            raw = b""
        return e.code, raw, dict(e.headers or {})
    except Exception as e:
        return None, b"", {"error": "%s: %s" % (type(e).__name__, e)}


# ------------------------------------------------------------ 图像工具 ----

def image_size(data):
    """返回 (width, height, fmt)。支持 PNG / JPEG；解析失败抛 ValueError。"""
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        if len(data) < 24 or data[12:16] != b"IHDR":
            raise ValueError("PNG 头不完整")
        w, h = struct.unpack(">II", data[16:24])
        return w, h, "png"
    if data[:2] == b"\xff\xd8":
        i = 2
        n = len(data)
        while i + 9 < n:
            if data[i] != 0xFF:
                i += 1
                continue
            marker = data[i + 1]
            if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
                i += 2
                continue
            seg_len = struct.unpack(">H", data[i + 2:i + 4])[0]
            if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                h, w = struct.unpack(">HH", data[i + 5:i + 9])
                return w, h, "jpeg"
            i += 2 + seg_len
        raise ValueError("JPEG 未找到 SOF 段")
    raise ValueError("未知图片格式（既非 PNG 也非 JPEG），magic=%r" % data[:4])


def magic_name(data):
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "PNG"
    if data[:2] == b"\xff\xd8":
        return "JPEG"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "WEBP"
    return "UNKNOWN(%r)" % data[:4]


def _png_chunk(tag, payload):
    return struct.pack(">I", len(payload)) + tag + payload + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)


def make_noise_png(width, height):
    """生成随机噪声 PNG（几乎不可压缩），用于 413 超大图用例。纯标准库。"""
    rows = []
    row_bytes = width * 3
    for _ in range(height):
        rows.append(b"\x00" + os.urandom(row_bytes))
    raw = b"".join(rows)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)  # 8bit truecolor RGB
    return (b"\x89PNG\r\n\x1a\n"
            + _png_chunk(b"IHDR", ihdr)
            + _png_chunk(b"IDAT", zlib.compress(raw, 1))
            + _png_chunk(b"IEND", b""))


def read_image(path):
    with open(path, "rb") as f:
        return f.read()


def save_out(name, data):
    os.makedirs(OUT_DIR, exist_ok=True)
    p = os.path.join(OUT_DIR, name)
    with open(p, "wb") as f:
        f.write(data)
    return p


# ------------------------------------------------------- 契约字段核对 ----

def check_bbox_consistency(elements, w, h, tol_px=1):
    """bbox_ratio 合法 + bbox_px == round(ratio*size)（容差 1px）。返回问题列表。"""
    problems = []
    for e in elements:
        eid = e.get("id")
        r = e.get("bbox_ratio")
        p = e.get("bbox_px")
        if not isinstance(r, list) or len(r) != 4:
            problems.append("id=%s bbox_ratio 不是长度 4 的数组: %r" % (eid, r))
            continue
        if not all(isinstance(v, (int, float)) and not isinstance(v, bool) for v in r):
            problems.append("id=%s bbox_ratio 含非数值: %r" % (eid, r))
            continue
        if not all(0.0 <= v <= 1.0 for v in r):
            problems.append("id=%s bbox_ratio 越界(未裁剪到[0,1]): %r" % (eid, r))
        if not (r[0] < r[2] and r[1] < r[3]):
            problems.append("id=%s bbox_ratio 不满足 x1<x2,y1<y2: %r" % (eid, r))
        if not isinstance(p, list) or len(p) != 4:
            problems.append("id=%s bbox_px 不是长度 4 的数组: %r" % (eid, p))
            continue
        if not all(isinstance(v, int) and not isinstance(v, bool) for v in p):
            problems.append("id=%s bbox_px 含非 int: %r" % (eid, p))
            continue
        if not (p[0] < p[2] and p[1] < p[3]):
            problems.append("id=%s bbox_px 不满足 x1<x2,y1<y2: %r" % (eid, p))
        expected = [round(r[0] * w), round(r[1] * h), round(r[2] * w), round(r[3] * h)]
        diff = max(abs(p[i] - expected[i]) for i in range(4))
        if diff > tol_px:
            problems.append("id=%s bbox_px 与 round(ratio*size) 相差 %dpx（容差 %d）：实际 %r 期望 %r（图 %dx%d）"
                            % (eid, diff, tol_px, p, expected, w, h))
    return problems


def check_order(elements, h, w=0, tol_px=ROW_BUCKET_PX):
    """编号顺序：先全部 text 再全部 icon；各自按上→下、同一行左→右。

    v1.1 修正（我自己的判据 bug，见验证报告 §15）：原来用「相邻两元素 centerY 差 ≤ 20px 即视为同一行 + 比较 x1」，
    与契约/实现共同采用的 **30px 行桶（对 centerY 分桶）** 不一致，会把落在相邻两个行桶的两个元素误当同一行，
    从而误报「同一行未从左到右」（8011 实测 id=39/40：cy=179.5 与 186.2 → 桶 5 与 桶 6，本就该按桶先后排）。
    现按契约口径判定：**行桶 = int(centerY_px // 30)，桶间非降；同桶内 centerX 非降**。
    另外返回「严格按 (y1,x1) 排序是否成立」与「同桶内 cy 颠倒的对数」，只作信息（30px 桶的必然现象），不判不通过。
    """
    problems = []
    types = [e.get("type") for e in elements]
    idx_icon = [i for i, t in enumerate(types) if t == "icon"]
    if idx_icon:
        first_icon = idx_icon[0]
        tail_text = [elements[i].get("id") for i in range(first_icon, len(elements)) if types[i] == "text"]
        if tail_text:
            problems.append("text 元素出现在 icon 元素之后：id=%r（首个 icon 位于下标 %d）" % (tail_text, first_icon))

    def geo(e):
        r = e.get("bbox_ratio") or [0, 0, 0, 0]
        cy = (r[1] + r[3]) / 2.0 * h
        cx_unit = (r[0] + r[2]) / 2.0
        cx_px = cx_unit * w if w else cx_unit
        return cy, cx_px, r[1] * h, r[0]

    inverted = []
    for i in range(1, len(elements)):
        a, b = elements[i - 1], elements[i]
        if types[i - 1] != types[i]:
            continue  # 跨组不做行内比较（组顺序已单测）
        cy_a, cx_a, y1_a, x1_a = geo(a)
        cy_b, cx_b, y1_b, x1_b = geo(b)
        bk_a, bk_b = int(cy_a // tol_px), int(cy_b // tol_px)
        if bk_b < bk_a:
            problems.append("同组顺序违反“从上到下”（30px 行桶）：id=%s(cy=%.1f,桶%d) 排在 id=%s(cy=%.1f,桶%d) 之后"
                            % (a.get("id"), cy_a, bk_a, b.get("id"), cy_b, bk_b))
        elif bk_a == bk_b:
            if cy_b < cy_a - 0.5:
                inverted.append((a.get("id"), round(cy_a, 1), b.get("id"), round(cy_b, 1)))
            if cx_b < cx_a - 0.5:
                problems.append("同一行桶(%d)内未按“从左到右”：id=%s(cx=%.1f) 排在 id=%s(cx=%.1f) 之前"
                                % (bk_b, a.get("id"), cx_a, b.get("id"), cx_b))

    ids = [e.get("id") for e in elements]
    strict = [e.get("id") for e in sorted(elements, key=lambda e: (geo(e)[2], geo(e)[3]))]
    return problems, {"strict_ok": strict == ids, "inverted": inverted, "tol": tol_px}


def check_element_fields(elements):
    problems = []
    for e in elements:
        eid = e.get("id")
        t = e.get("type")
        if t not in ("text", "icon"):
            problems.append("id=%s type 非法: %r" % (eid, t))
        if t == "text":
            if e.get("source") != "ocr":
                problems.append("id=%s text 元素 source 应为 \"ocr\"，实际 %r" % (eid, e.get("source")))
            if e.get("label") != e.get("text"):
                problems.append("id=%s text 元素 label 应等于 text，实际 label=%r text=%r"
                                % (eid, e.get("label"), e.get("text")))
            if not (isinstance(e.get("text"), str) and e.get("text") != ""):
                problems.append("id=%s text 元素 text 为空: %r" % (eid, e.get("text")))
        elif t == "icon":
            if e.get("source") != "icon":
                problems.append("id=%s icon 元素 source 应为 \"icon\"，实际 %r" % (eid, e.get("source")))
            if e.get("text") != "":
                problems.append("id=%s icon 元素 text 应为 \"\"，实际 %r" % (eid, e.get("text")))
            if not (isinstance(e.get("label"), str) and e.get("label") != ""):
                problems.append("id=%s icon 元素 label 为空: %r" % (eid, e.get("label")))
            if e.get("interactable") is not True:
                problems.append("id=%s YOLO 图标应恒 interactable=true，实际 %r" % (eid, e.get("interactable")))
        if not isinstance(e.get("interactable"), bool):
            problems.append("id=%s interactable 不是 bool: %r" % (eid, e.get("interactable")))
    return problems


def check_ids(elements):
    ids = [e.get("id") for e in elements]
    bad = [i for i in ids if not isinstance(i, int) or isinstance(i, bool)]
    if bad:
        return ["id 含非 int: %r" % bad]
    if len(set(ids)) != len(ids):
        dup = sorted({i for i in ids if ids.count(i) > 1})
        return ["id 不唯一，重复: %r" % dup]
    if ids != list(range(1, len(ids) + 1)):
        return ["id 不是 1 起按序连续：实际 %r（长度 %d）" % (ids[:15], len(ids))]
    return []


# ---------------------------------------------------------------- 用例 ----

def run_selftest():
    print("=== 离线自检（不联网）=== ", flush=True)
    emit_selftest_pass = True

    # 1) PNG/JPEG 尺寸解析
    for name in ("q2.png", "quiz.png"):
        p = os.path.join(SHOTS_DIR, name)
        if not os.path.exists(p):
            emit("不通过", "自检·测试图存在 " + name, "找不到 " + p)
            emit_selftest_pass = False
            continue
        data = read_image(p)
        try:
            w, h, fmt = image_size(data)
            emit("通过", "自检·解析 %s 尺寸" % name, "%dx%d fmt=%s bytes=%d" % (w, h, fmt, len(data)))
        except Exception as e:
            emit("不通过", "自检·解析 %s 尺寸" % name, repr(e))
            emit_selftest_pass = False

    # 2) 超大 PNG 生成器
    try:
        big = make_noise_png(2200, 2200)
        w, h, fmt = image_size(big)
        ok = len(big) > 12 * 1024 * 1024
        emit("通过" if ok else "不通过", "自检·生成 >12MB 噪声 PNG",
             "bytes=%d (%.2f MB) %dx%d" % (len(big), len(big) / 1048576.0, w, h))
        if not ok:
            emit_selftest_pass = False
    except Exception as e:
        emit("不通过", "自检·生成 >12MB 噪声 PNG", repr(e))
        emit_selftest_pass = False

    # 3) 字段核对逻辑的正/负样本（自证检查器真的会报错，不是永远打印通过）
    good = [{"id": 1, "type": "text", "label": "关闭", "text": "关闭",
             "bbox_ratio": [0.1, 0.1, 0.2, 0.12], "bbox_px": [72, 160, 144, 192],
             "interactable": True, "source": "ocr"},
            {"id": 2, "type": "icon", "label": "magnifying glass", "text": "",
             "bbox_ratio": [0.3, 0.4, 0.35, 0.45], "bbox_px": [216, 640, 252, 720],
             "interactable": True, "source": "icon"}]
    p = check_ids(good) + check_bbox_consistency(good, 720, 1600) + check_order(good, 1600)[0] + check_element_fields(good)
    emit("通过" if not p else "不通过", "自检·正样本应无问题", repr(p) if p else "无问题")
    emit_selftest_pass &= (not p)

    bad = [dict(good[0], id=2), dict(good[1], id=2, bbox_px=[0, 0, 0, 0], text="x")]
    p2 = check_ids(bad) + check_bbox_consistency(bad, 720, 1600) + check_element_fields(bad)
    ok2 = len(p2) >= 3
    emit("通过" if ok2 else "不通过", "自检·负样本应被挑出（≥3 个问题）", "检出 %d 条: %s" % (len(p2), p2[:3]))
    emit_selftest_pass &= ok2

    print("\n自检结论: " + ("通过" if emit_selftest_pass else "不通过"), flush=True)
    return 0 if emit_selftest_pass else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default=os.environ.get("OMNI_BASE_URL", "http://127.0.0.1:8010"))
    ap.add_argument("--image", default="q2.png", help="真实 PNG 测试图（shots\\ 下）")
    ap.add_argument("--image2", default="quiz.png")
    ap.add_argument("--timeout", type=int, default=600, help="/parse 正样本（含推理）的读超时")
    ap.add_argument("--error-timeout", type=int, default=60, help="错误路径用例的读超时（服务串行化时可能排队）")
    ap.add_argument("--skip-parse", action="store_true",
                    help="只跑 /health + 错误路径 + 状态码（跳过需要真实推理的 /parse 正样本、annotate、截断、阈值用例）")
    ap.add_argument("--label", default="", help="本次采证的标注，如 '8010 real+caption'，会写进输出与结构化结果")
    ap.add_argument("--skip-heavy", action="store_true", help="跳过超大图 413 与并发用例")
    ap.add_argument("--selftest", action="store_true", help="离线自检脚本自身")
    args = ap.parse_args()

    global MODE, LABEL
    LABEL = args.label
    print("独立验证 · 解析服务契约  base_url=%s  时间=%s" % (args.base_url, time.strftime("%Y-%m-%d %H:%M:%S")), flush=True)
    print("口径：docs/接口约定-SoM与解析服务.md（冻结契约，2026-10-10 18:47 起为 v1.1）", flush=True)
    if args.label:
        print("本次标注：%s%s" % (args.label, "（--skip-parse：只跑 /health + 错误路径）" if args.skip_parse else ""), flush=True)

    if args.selftest:
        return run_selftest()

    os.makedirs(OUT_DIR, exist_ok=True)

    # ---------- 0. 服务可达性 ----------
    status, raw, headers = http_call("GET", args.base_url.rstrip("/") + "/health", timeout=15)
    if status is None:
        print("\n[未验证] 服务不可达（%s）—— %s" % (args.base_url, headers.get("error")), flush=True)
        print("环境不可用：/health 与 /parse 全部用例未验证。请先让解析服务监听该端口（问 lead / omni-service），不要由验证脚本代起服务。", flush=True)
        return 2
    save_out("health.json", raw)

    # ---------- 1. /health ----------
    emit("通过" if status == 200 else "不通过", "H1 GET /health HTTP 200", "实际 HTTP %s" % status)
    ctype = headers.get("Content-Type") or headers.get("content-type") or ""
    ok_ct = "application/json" in ctype.lower()
    emit("通过" if ok_ct else "不通过", "H2 Content-Type 含 application/json", "实际 %r（契约写 application/json; charset=utf-8）" % ctype)
    if "charset=utf-8" not in ctype.lower().replace(" ", ""):
        note("H2 备注：Content-Type 未显式带 charset=utf-8（契约字面为 application/json; charset=utf-8）")

    hj, jerr = parse_json(raw)
    if hj is None:
        emit("不通过", "H3 /health 响应是合法 JSON", jerr)
        return 1
    required = {"ok": bool, "service": str, "version": str, "mode": str, "backend": str,
                "device": str, "caption_enabled": bool, "weights": dict, "uptime_s": (int, float)}
    missing = [k for k in required if k not in hj]
    wrongtype = [k for k, t in required.items() if k in hj and not isinstance(hj[k], t)]
    emit("通过" if not missing and not wrongtype else "不通过", "H4 /health 必填字段齐全且类型正确",
         "缺失=%r 类型错=%r" % (missing, wrongtype))
    emit("通过" if hj.get("ok") is True else "不通过", "H5 /health ok=true", "实际 %r" % hj.get("ok"))
    emit("通过" if hj.get("service") == "omniparser-service" else "不通过",
         "H6 service=omniparser-service", "实际 %r" % hj.get("service"))
    emit("通过" if hj.get("version") == "1.0" else "不通过",
         "H7 version=1.0（契约示例值）", "实际 %r" % hj.get("version"))
    MODE = hj.get("mode")
    emit("通过" if MODE in ("real", "mock") else "不通过", "H8 mode ∈ {real,mock}", "实际 %r" % MODE)
    emit("通过" if hj.get("backend") in ("lite", "upstream") else "不通过",
         "H9 backend ∈ {lite,upstream}", "实际 %r" % hj.get("backend"))
    emit("通过" if hj.get("device") in ("cpu", "cuda") else "不通过",
         "H10 device ∈ {cpu,cuda}", "实际 %r" % hj.get("device"))
    w = hj.get("weights") or {}
    if MODE == "real":
        ok_w = "som_model" in w and "caption_model" in w
        emit("通过" if ok_w else "不通过", "H11 real 模式 weights 含 som_model/caption_model", "实际 %r" % w)
    else:
        note("H11 跳过：mode != real，按契约 mock 不需要权重")
    if isinstance(hj.get("uptime_s"), (int, float)) and hj.get("uptime_s") >= 0:
        emit("通过", "H12 uptime_s 非负数值", "实际 %r" % hj.get("uptime_s"))
    else:
        emit("不通过", "H12 uptime_s 非负数值", "实际 %r" % hj.get("uptime_s"))

    if MODE != "real":
        note("当前服务 mode=%r ⇒ 下文所有 elements 类结论均为「mock，非真实模型」，不能当作真实识别能力证据。" % MODE)

    # 以下 2–4 节都需要真实推理（CPU 上 caption 单张可达数分钟，服务端还有锁串行化），
    # 用 --skip-parse 可只跑 /health + 错误路径 + 状态码，待有快速实例（如 --no-caption）再跑全量。
    if args.skip_parse:
        note("--skip-parse：跳过需要真实推理的 /parse 正样本、annotate、max_elements、box_threshold 用例（这些留到快实例再跑，避免在服务锁上排队）")
    else:
        # ---------- 2. 真实图 /parse 基线 ----------
        img_name = args.image
        img_path = os.path.join(SHOTS_DIR, img_name)
        if not os.path.exists(img_path):
            emit("不通过", "P0 测试图存在", "找不到 %s" % img_path)
            return 1
        img_bytes = read_image(img_path)
        iw, ih, ifmt = image_size(img_bytes)
        b64 = base64.b64encode(img_bytes).decode("ascii")
        note("测试图 %s：%dx%d %s，%d 字节，base64 %d 字符（不带 data: 前缀）" % (img_name, iw, ih, ifmt, len(img_bytes), len(b64)))

        t0 = time.time()
        st, raw, headers = post_parse(args.base_url, {"image_base64": b64, "annotate": False}, timeout=args.timeout)
        wall = (time.time() - t0) * 1000
        save_out("parse_baseline_%s.json" % os.path.splitext(img_name)[0], raw)
        emit("通过" if st == 200 else "不通过", "P1 POST /parse(annotate=false) HTTP 200", "实际 HTTP %s，网络错误=%r" % (st, headers.get("error")))
        if st != 200:
            note("P1 失败后，后续字段类用例标记为未验证（无有效响应体）")
            dump_summary()
            return 1
        pj, perr = parse_json(raw)
        if pj is None:
            emit("不通过", "P2 /parse 响应是合法 JSON", perr)
            dump_summary()
            return 1
        emit("通过" if pj.get("ok") is True else "不通过", "P2 ok=true", "实际 %r" % pj.get("ok"))
        emit("通过" if pj.get("mode") in ("real", "mock") else "不通过", "P3 mode ∈ {real,mock}", "实际 %r" % pj.get("mode"))
        im = pj.get("image") or {}
        ok_img = im.get("width") == iw and im.get("height") == ih
        emit("通过" if ok_img else "不通过", "P4 image.width/height == 提交图像素尺寸",
             "提交 %dx%d，返回 %rx%r" % (iw, ih, im.get("width"), im.get("height")))
        emit("通过" if isinstance(pj.get("elapsed_ms"), int) else "不通过", "P5 elapsed_ms 是 int",
             "实际 %r（客户端实测 wall=%.0fms；契约说服务端本次推理耗时）" % (pj.get("elapsed_ms"), wall))
        els = pj.get("elements")
        if not isinstance(els, list) or not els:
            emit("不通过", "P6 elements 是非空数组", "实际 %r" % (type(els).__name__ if els is not None else None))
            dump_summary()
            return 1
        emit("通过", "P6 elements 是非空数组", "共 %d 个元素" % len(els))
        emit("通过" if "som_image_base64" not in pj else "不通过", "P7 annotate=false 时不返回 som_image_base64",
             "实际存在=%r（契约：仅 annotate=true 时存在）" % ("som_image_base64" in pj))

        idp = check_ids(els)
        emit("通过" if not idp else "不通过", "P8 id 1 起、唯一、按序连续", "; ".join(idp) if idp else "ids=1..%d" % len(els))
        bbp = check_bbox_consistency(els, iw, ih)
        emit("通过" if not bbp else "不通过", "P9 bbox_ratio 合法 + bbox_px==round(ratio*size)（±1px）",
             "共 %d 处问题：%s" % (len(bbp), " | ".join(bbp[:3])) if bbp else "全部一致")
        fldp = check_element_fields(els)
        emit("通过" if not fldp else "不通过", "P10 type/source/label/text/interactable 语义",
             "共 %d 处问题：%s" % (len(fldp), " | ".join(fldp[:3])) if fldp else "全部符合")
        ordp, ordinfo = check_order(els, ih, iw)
        emit("通过" if not ordp else "不通过",
             "P11 顺序：先全部 text 后全部 icon，各自上→下/左→右（%dpx 行桶，同桶比 centerX）" % ROW_BUCKET_PX,
             "共 %d 处问题：%s" % (len(ordp), " | ".join(ordp[:3])) if ordp else "顺序正确（按契约 30px 行桶口径）")
        emit("通过" if ordinfo["strict_ok"] else "注意",
             "P11b 附加信息：严格按 (y1,x1) 全序排序是否也成立",
             "成立（比契约口径更严）" if ordinfo["strict_ok"]
             else "不成立：同一 30px 行桶内 centerY 颠倒 %d 对（30px 行桶口径的必然现象，非违例；前 3 对=%r）"
                  % (len(ordinfo["inverted"]), ordinfo["inverted"][:3]))
        n_text = sum(1 for e in els if e.get("type") == "text")
        n_icon = sum(1 for e in els if e.get("type") == "icon")
        note("元素构成：text=%d icon=%d；source 分布=%r" % (n_text, n_icon, sorted({str(e.get("source")) for e in els})))
        note("真实 elements 片段（前 3 个，mode=%s）：%s" % (MODE, json.dumps(els[:3], ensure_ascii=False)[:1200]))

        # ---------- 3. annotate=true ----------
        st2, raw2, _ = post_parse(args.base_url, {"image_base64": b64, "annotate": True}, timeout=args.timeout)
        save_out("parse_annotate_%s.json" % os.path.splitext(img_name)[0], raw2)
        emit("通过" if st2 == 200 else "不通过", "P12 POST /parse(annotate=true) HTTP 200", "实际 HTTP %s" % st2)
        if st2 == 200:
            pj2, perr2 = parse_json(raw2)
            if pj2 is None:
                emit("不通过", "P13 annotate 响应是合法 JSON", perr2)
            else:
                som = pj2.get("som_image_base64")
                if not isinstance(som, str) or not som:
                    emit("不通过", "P13 annotate=true 时返回非空 som_image_base64", "实际 %r" % (type(som).__name__ if som is not None else None))
                else:
                    try:
                        try:
                            som_bytes = base64.b64decode(som, validate=True)
                        except Exception:
                            som_bytes = base64.b64decode(som)  # 宽松：容忍换行/填充差异，但要记账
                            note("P13 备注：som_image_base64 严格 validate=True 解码失败，宽松解码成功（body 含换行等非严格字符）")
                        sw, sh, sfmt = image_size(som_bytes)
                        p_som = save_out("som_%s.%s" % (os.path.splitext(img_name)[0], sfmt), som_bytes)
                        emit("通过" if (sw == iw and sh == ih) else "不通过",
                             "P13 som 解码成功且尺寸 == 提交图", "%s %dx%d（提交 %dx%d），落盘 %s" % (magic_name(som_bytes), sw, sh, iw, ih, p_som))
                        emit("通过" if sfmt == "jpeg" else "不通过", "P14 som 编码为 JPEG（契约字段表写 JPEG base64）",
                             "实际 magic=%s" % magic_name(som_bytes))
                    except Exception as e:
                        emit("不通过", "P13 som_image_base64 可解码为图片", "%s: %s" % (type(e).__name__, e))
                els2 = pj2.get("elements")
                if isinstance(els2, list) and els2:
                    # 编号与 elements[].id 一致 —— 图上编号无法机器识别，改为核对 annotate 前后 elements 完全一致（编号来源相同）
                    same = json.dumps(els2, sort_keys=True) == json.dumps(els, sort_keys=True)
                    emit("通过" if same else "注意", "P15 annotate=true 与 false 的 elements 一致（间接核对编号来源）",
                         "一致" if same else "两次调用 elements 不同（可能是模型/阈值抖动，需人工看 som 图编号）")
                else:
                    emit("不通过", "P15 annotate 响应 elements 非空", "实际 %r" % (type(els2).__name__ if els2 is not None else None))

        # ---------- 4. max_elements / box_threshold ----------
        st3, raw3, _ = post_parse(args.base_url, {"image_base64": b64, "max_elements": 3}, timeout=args.timeout)
        save_out("parse_max3.json", raw3)
        if st3 == 200:
            pj3, _ = parse_json(raw3)
            e3 = (pj3 or {}).get("elements") or []
            ok3 = len(e3) <= 3 and [e.get("id") for e in e3] == list(range(1, len(e3) + 1))
            emit("通过" if ok3 else "不通过", "P16 max_elements=3 截断且保序（id=1..N, N<=3）",
                 "返回 %d 个，ids=%r（基线 %d 个）" % (len(e3), [e.get("id") for e in e3], len(els)))
            if len(e3) and isinstance(els, list) and len(els) >= len(e3):
                head_same = [e.get("bbox_px") for e in e3] == [e.get("bbox_px") for e in els[:len(e3)]]
                emit("通过" if head_same else "不通过", "P17 截断是保序前缀（与基线前 N 个一致）", "一致" if head_same else "前缀不一致")
        else:
            emit("不通过", "P16 max_elements=3 请求成功", "HTTP %s" % st3)

        st4, raw4, _ = post_parse(args.base_url, {"image_base64": b64, "box_threshold": 0.9}, timeout=args.timeout)
        if st4 == 200:
            pj4, _ = parse_json(raw4)
            e4 = (pj4 or {}).get("elements") or []
            emit("通过" if len(e4) <= len(els) else "注意", "P18 box_threshold=0.9 被接受且元素数不增",
                 "阈值0.9 返回 %d 个 vs 默认 %d 个" % (len(e4), len(els)))
        else:
            emit("不通过", "P18 box_threshold=0.9 请求成功", "HTTP %s" % st4)

    # ---------- 5. 错误路径 ----------
    st5, raw5, _ = post_parse(args.base_url, {"image_base64": "!!!not_base64!!!"}, timeout=args.error_timeout)
    save_out("err_badbase64.txt", raw5)
    emit("通过" if st5 == 400 else "不通过", "E1 坏 base64 → 400", "实际 HTTP %s body=%s" % (st5, raw5[:200]))
    if st5 in (400, 413, 500, 503):
        ej, _ = parse_json(raw5)
        ok = isinstance(ej, dict) and ej.get("ok") is False and isinstance(ej.get("error"), str) and ej.get("error")
        emit("通过" if ok else "不通过", "E2 错误体统一为 {\"ok\": false, \"error\": \"<人话>\"}", "实际 %s" % raw5[:200].decode("utf-8", "replace"))

    st6, raw6, _ = post_parse(args.base_url, {}, timeout=args.error_timeout)
    save_out("err_missing_field.txt", raw6)
    emit("通过" if st6 == 400 else "不通过", "E3 缺 image_base64 字段 → 400", "实际 HTTP %s body=%s" % (st6, raw6[:200]))

    st7, raw7, _ = post_parse(args.base_url, raw_body=b"{not json at all", timeout=args.error_timeout)
    emit("通过" if st7 == 400 else "不通过", "E4 请求体不是合法 JSON → 400", "实际 HTTP %s body=%s" % (st7, raw7[:200]))

    b64_text = base64.b64encode(b"hello, not an image").decode("ascii")
    st8, raw8, _ = post_parse(args.base_url, {"image_base64": b64_text}, timeout=args.error_timeout)
    emit("通过" if st8 == 400 else "不通过", "E5 合法 base64 但非图片 → 400", "实际 HTTP %s body=%s" % (st8, raw8[:200]))

    if not args.skip_heavy:
        try:
            big = make_noise_png(2200, 2200)
            emit("通过", "E6·准备超大图", "%d 字节（%.2f MB），解码后 > 12MB" % (len(big), len(big) / 1048576.0))
            big_b64 = base64.b64encode(big).decode("ascii")
            st9, raw9, _ = post_parse(args.base_url, {"image_base64": big_b64}, timeout=args.error_timeout)
            save_out("err_oversize.txt", raw9)
            emit("通过" if st9 == 413 else "不通过", "E6 超大图(>12MB 解码后) → 413", "实际 HTTP %s body=%s" % (st9, raw9[:200]))
        except Exception as e:
            emit("不通过", "E6 超大图 413 用例执行", "%s: %s" % (type(e).__name__, e))

    # ---------- 6. 并发/幂等 ----------
    if not args.skip_heavy:
        results = []
        errors = []

        def worker(i):
            t = time.time()
            s, r, h = post_parse(args.base_url, {"image_base64": b64, "max_elements": 5}, timeout=args.timeout)
            results.append((i, s, r, (time.time() - t) * 1000))
            if s is None:
                errors.append(h.get("error"))

        ths = [threading.Thread(target=worker, args=(i,)) for i in range(3)]
        tt = time.time()
        for t in ths:
            t.start()
        for t in ths:
            t.join()
        total = (time.time() - tt) * 1000
        codes = [r[1] for r in results]
        all200 = codes == [200, 200, 200]
        emit("通过" if all200 else "不通过", "P19 3 个并发 /parse 全部 200（契约：可并发）",
             "codes=%r 总耗时=%.0fms 各耗时=%r 网络错误=%r" % (codes, total, ["%.0f" % r[3] for r in results], errors))
        if all200:
            bodies = {json.dumps((parse_json(r[2])[0] or {}).get("elements"), sort_keys=True) for r in results}
            emit("通过" if len(bodies) == 1 else "注意", "P20 并发结果一致（无副作用/幂等）",
                 "3 次响应 elements 有 %d 种不同结果（相同输入下不一致需人工判断是否为模型抖动）" % len(bodies))

    return dump_summary()


def dump_summary():
    n_pass = sum(1 for k, _, _ in RESULTS if k == "通过")
    n_fail = sum(1 for k, _, _ in RESULTS if k == "不通过")
    n_warn = sum(1 for k, _, _ in RESULTS if k == "注意")
    n_unver = sum(1 for k, _, _ in RESULTS if k == "未验证")
    print("\n================ 汇总 ================", flush=True)
    print("模式(mode) = %s%s" % (MODE, "   ← mock，非真实模型" if MODE == "mock" else ""), flush=True)
    print("通过 %d 项 / 不通过 %d 项 / 注意 %d 项 / 未验证 %d 项" % (n_pass, n_fail, n_warn, n_unver), flush=True)
    if n_fail:
        print("不通过清单：", flush=True)
        for k, item, detail in RESULTS:
            if k == "不通过":
                print("  - %s  (%s)" % (item, detail), flush=True)
    try:
        os.makedirs(OUT_DIR, exist_ok=True)
        with open(os.path.join(OUT_DIR, "service-contract-result.json"), "w", encoding="utf-8") as f:
            json.dump({"mode": MODE, "label": LABEL, "results": [{"kind": k, "item": i, "detail": d} for k, i, d in RESULTS],
                       "notes": NOTES, "time": time.strftime("%Y-%m-%d %H:%M:%S")}, f, ensure_ascii=False, indent=2)
        print("结构化结果：%s" % os.path.join(OUT_DIR, "service-contract-result.json"), flush=True)
    except Exception:
        traceback.print_exc()
    return 0 if n_fail == 0 else 1


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
