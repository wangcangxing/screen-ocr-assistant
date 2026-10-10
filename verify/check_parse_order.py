#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
独立复核 · `/parse` 的编号顺序（冻结契约 §1.2）
=================================================
口径：`docs\\接口约定-SoM与解析服务.md` §1.2 ——
  「elements[].id：1 起、唯一、按序：先全部文字元素（按 bbox 从上到下、从左到右），再全部图标元素（同样排序）」。

与 check_service_contract.py 的区别：本脚本**专门**做顺序复核，且
  - 用**真实像素值**（bbox_ratio × 提交图尺寸）判定，不用比值近似；
  - **覆盖全部元素**（不是只看前 5 条），并打印每一行桶的完整成员便于人工核对；
  - 同时给出「严格 (y1,x1)」读法是否成立，供判断 30px 行桶带来的偏差。

用法：
  python verify\\check_parse_order.py --base-url http://127.0.0.1:8011 --label "8011 real-no-caption"
  python verify\\check_parse_order.py --from-json verify\\out\\order_8011_q2.json      # 复核同一次响应
  python verify\\check_parse_order.py --selftest                                        # 离线自检判据本身

退出码：0 = 顺序复核通过；1 = 有不通过项；2 = 环境不可用（未验证）。
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

ROW_BUCKET_PX = 30  # 契约 §2.1 的「同一行」容差；服务端实现同样用 30px（server.py ROW_BUCKET_PX）

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
            if m in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                h, w = struct.unpack(">HH", data[i + 5:i + 9])
                return w, h
            i += 2 + L
        raise ValueError("JPEG 未找到 SOF")
    raise ValueError("未知图片格式")


def post_parse(base_url, payload, timeout):
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(base_url.rstrip("/") + "/parse", data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:
        return None, ("%s: %s" % (type(e).__name__, e)).encode("utf-8")


# --------------------------------------------------------------- 判据 ----

def px_of(el, w, h):
    """返回真实像素框 (x1,y1,x2,y2, cx, cy)。优先 bbox_ratio；缺失时用 bbox_px。"""
    r = el.get("bbox_ratio")
    if isinstance(r, list) and len(r) == 4:
        x1, y1, x2, y2 = (float(v) * (w if i % 2 == 0 else h) for i, v in enumerate(r))
        return x1, y1, x2, y2, (x1 + x2) / 2.0, (y1 + y2) / 2.0
    p = el.get("bbox_px")
    if isinstance(p, list) and len(p) == 4:
        x1, y1, x2, y2 = (float(v) for v in p)
        return x1, y1, x2, y2, (x1 + x2) / 2.0, (y1 + y2) / 2.0
    return None


def analyze(elements, w, h):
    """核心判据：R1 id、R2 组顺序、R3 行桶非降、R4 同桶内左→右。返回 (问题列表, 事实字典)。"""
    problems = []
    facts = {}

    ids = [e.get("id") for e in elements]
    if ids != list(range(1, len(ids) + 1)):
        problems.append("R1 id 不是 1..N 连续唯一：实际 %r" % (ids[:20],))

    types = [e.get("type") for e in elements]
    idx_icon = [i for i, t in enumerate(types) if t == "icon"]
    if idx_icon:
        tail_text = [(i, elements[i].get("id")) for i in range(idx_icon[0], len(elements)) if types[i] == "text"]
        if tail_text:
            problems.append("R2 有 text 元素排在 icon 之后：下标/id=%r（首个 icon 在下标 %d）" % (tail_text[:10], idx_icon[0]))

    boxes = [px_of(e, w, h) for e in elements]
    missing = [e.get("id") for e, b in zip(elements, boxes) if b is None]
    if missing:
        problems.append("R6 缺 bbox：id=%r" % missing[:10])
        return problems, facts

    # R3 / R4：按组分别检查
    for group in ("text", "icon"):
        seq = [(e.get("id"), b, t) for e, b, t in zip(elements, boxes, types) if t == group]
        if len(seq) < 2:
            continue
        for k in range(1, len(seq)):
            pid, pb, _ = seq[k - 1]
            cid, cb, _ = seq[k]
            pbucket, cbucket = int(pb[5] // ROW_BUCKET_PX), int(cb[5] // ROW_BUCKET_PX)
            if cbucket < pbucket:
                problems.append("R3 %s 组「上→下」被违反：id=%s(centerY=%.1f,行桶%d) 排在 id=%s(centerY=%.1f,行桶%d) 之后"
                                % (group, pid, pb[5], pbucket, cid, cb[5], cbucket))
            elif cbucket == pbucket and cb[4] < pb[4] - 0.5:
                problems.append("R4 %s 组同一行桶(%d)内「左→右」被违反：id=%s(cx=%.1f) 排在 id=%s(cx=%.1f) 之后"
                                % (group, cbucket, pid, pb[4], cid, cb[4]))

    # 事实：严格 (y1,x1) 排序是否成立；同桶内上下颠倒的对数
    strict = [e.get("id") for e in sorted(elements, key=lambda e: (px_of(e, w, h)[1], px_of(e, w, h)[0]))]
    facts["strict_y1x1_ok"] = strict == ids
    inverted = []
    for group in ("text", "icon"):
        seq = [(e.get("id"), b) for e, b, t in zip(elements, boxes, types) if t == group]
        for k in range(1, len(seq)):
            pid, pb = seq[k - 1]
            cid, cb = seq[k]
            if int(pb[5] // ROW_BUCKET_PX) == int(cb[5] // ROW_BUCKET_PX) and cb[5] < pb[5] - 0.5:
                inverted.append((pid, round(pb[5], 1), cid, round(cb[5], 1)))
    facts["same_bucket_inverted"] = inverted

    # R6 真实像素交叉核对：bbox_px == round(ratio*size)
    bad_px = []
    for e in elements:
        r, p = e.get("bbox_ratio"), e.get("bbox_px")
        if not (isinstance(r, list) and isinstance(p, list) and len(r) == 4 and len(p) == 4):
            continue
        exp = [round(r[0] * w), round(r[1] * h), round(r[2] * w), round(r[3] * h)]
        if max(abs(p[i] - exp[i]) for i in range(4)) > 1:
            bad_px.append((e.get("id"), p, exp))
    facts["bad_px"] = bad_px
    if bad_px:
        problems.append("R6 bbox_px 与 round(ratio*size) 不一致（±1px）：%r" % bad_px[:5])
    return problems, facts


def print_rows(elements, w, h):
    boxes = [px_of(e, w, h) for e in elements]
    rows = {}
    order = []
    for e, b in zip(elements, boxes):
        if b is None:
            continue
        bk = int(b[5] // ROW_BUCKET_PX)
        if bk not in rows:
            rows[bk] = []
            order.append(bk)
        rows[bk].append((e.get("id"), b))
    print("      行桶  返回顺序的 id           y 中心范围(px)      x 中心范围(px)")
    for bk in order:
        item = rows[bk]
        ys = [b[5] for _, b in item]
        xs = [b[4] for _, b in item]
        print("      %-5d %-22s %-18s %s" % (bk, str([i for i, _ in item]),
                                             "%.1f–%.1f" % (min(ys), max(ys)), "%.1f–%.1f" % (min(xs), max(xs))))


# --------------------------------------------------------------- 自检 ----

def synth(entries, w=1080, h=2400):
    els = []
    for i, (t, label, r) in enumerate(entries, start=1):
        els.append({"id": i, "type": t, "label": label, "text": label if t == "text" else "",
                    "bbox_ratio": list(r), "bbox_px": [round(r[0] * w), round(r[1] * h), round(r[2] * w), round(r[3] * h)],
                    "interactable": True, "source": "ocr" if t == "text" else "icon"})
    return els


def run_selftest():
    ok_all = True
    cases = [
        ("正样本：text 两行 + icon 两行，各自上→下/左→右",
         synth([("text", "A", (0.1, 0.10, 0.3, 0.12)), ("text", "B", (0.1, 0.20, 0.3, 0.22)),
                ("icon", "i1", (0.8, 0.10, 0.9, 0.12)), ("icon", "i2", (0.8, 0.20, 0.9, 0.22))]), True),
        ("行桶边界（同一 30px 桶内，先出现的 cx 更小）→ 通过",
         synth([("text", "A", (0.1, 0.041, 0.3, 0.045)), ("text", "B", (0.5, 0.045, 0.7, 0.049))]), True),
        ("R3 违反：行桶 5 的排在行桶 3 之后",
         synth([("text", "B", (0.1, 0.15, 0.3, 0.17)), ("text", "A", (0.1, 0.05, 0.3, 0.07))]), False),
        ("R4 违反：同一行桶内 cx 递减",
         synth([("text", "B", (0.6, 0.10, 0.9, 0.12)), ("text", "A", (0.1, 0.10, 0.3, 0.12))]), False),
        ("R2 违反：icon 排在 text 之前",
         synth([("icon", "i1", (0.8, 0.10, 0.9, 0.12)), ("text", "A", (0.1, 0.20, 0.3, 0.22))]), False),
    ]
    for name, els, expect_pass in cases:
        problems, _ = analyze(els, 1080, 2400)
        got_pass = not problems
        ok = got_pass == expect_pass
        ok_all = ok_all and ok
        print("[%s] 自检·%s → %s%s" % ("通过" if ok else "不通过", name,
                                      "无问题" if got_pass else "检出 %d 条" % len(problems),
                                      "" if got_pass else "：" + problems[0][:90]))
    # id 不连续
    els = synth([("text", "A", (0.1, 0.10, 0.3, 0.12)), ("text", "B", (0.1, 0.20, 0.3, 0.22))])
    els[1]["id"] = 3
    problems, _ = analyze(els, 1080, 2400)
    ok_id = any(p.startswith("R1") for p in problems)
    ok_all = ok_all and ok_id
    print("[%s] 自检·R1 id 不连续会被检出 → %r" % ("通过" if ok_id else "不通过", ok_id))
    print("\n自检结论：%s" % ("通过" if ok_all else "不通过"))
    return 0 if ok_all else 1


# --------------------------------------------------------------- 主流程 ----

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://127.0.0.1:8011")
    ap.add_argument("--image", default="q2.png")
    ap.add_argument("--timeout", type=int, default=600)
    ap.add_argument("--from-json", default="", help="直接分析已保存的 /parse 响应（不再发请求）")
    ap.add_argument("--save-json", default="", help="把本次响应另存为指定文件名（落在 verify\\out\\）")
    ap.add_argument("--expect-icon-label", default="", help="如 8011(--no-caption) 期望 icon 的 label 恒为该值")
    ap.add_argument("--label", default="", help="本次采证标注（端口/模式）")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return run_selftest()

    os.makedirs(OUT_DIR, exist_ok=True)
    print("独立复核 · /parse 编号顺序（契约 §1.2）  时间=%s  标注=%s" % (time.strftime("%Y-%m-%d %H:%M:%S"), args.label or "(无)"))

    if args.from_json:
        path = args.from_json if os.path.isabs(args.from_json) else os.path.join(ROOT, args.from_json)
        raw = open(path, "rb").read()
        print("数据来源：%s（不再发请求）" % path)
    else:
        img_path = os.path.join(SHOTS_DIR, args.image)
        data = open(img_path, "rb").read()
        w, h = image_size(data)
        b64 = base64.b64encode(data).decode("ascii")
        print("测试图 %s：%dx%d %d 字节 → POST %s/parse（annotate=false）" % (args.image, w, h, len(data), args.base_url))
        t0 = time.time()
        st, raw = post_parse(args.base_url, {"image_base64": b64, "annotate": False}, args.timeout)
        wall = time.time() - t0
        if st is None:
            print("\n[未验证] 请求失败：%s" % raw.decode("utf-8", "replace"))
            return 2
        emit("通过" if st == 200 else "不通过", "O0 POST /parse HTTP 200", "实际 HTTP %s，客户端实测 %.1fs" % (st, wall))
        if st != 200:
            return 1
        name = args.save_json or ("order_%s.json" % os.path.splitext(args.image)[0])
        p = os.path.join(OUT_DIR, name)
        open(p, "wb").write(raw)
        print("响应已存：%s" % p)

    payload = json.loads(raw.decode("utf-8"))
    im = payload.get("image") or {}
    # 提交图尺寸：响应里的 image.width/height 是契约要求的「提交图片」尺寸
    w, h = int(im.get("width") or 0), int(im.get("height") or 0)
    if w <= 0 or h <= 0:
        print("[不通过] 响应缺 image.width/height，无法用真实像素复核")
        return 1
    els = payload.get("elements")
    if not isinstance(els, list) or not els:
        print("[不通过] elements 为空（%r）—— 注意：纯黑/无内容图 0 元素可能是预期，本脚本无法据此判顺序" % els)
        return 1

    n_text = sum(1 for e in els if e.get("type") == "text")
    n_icon = sum(1 for e in els if e.get("type") == "icon")
    print("元素 %d 个（text %d / icon %d），提交图 %dx%d，模式 mode=%r，elapsed_ms=%r"
          % (len(els), n_text, n_icon, w, h, payload.get("mode"), payload.get("elapsed_ms")))

    problems, facts = analyze(els, w, h)
    emit("通过" if not any(p.startswith("R1") for p in problems) else "不通过", "O1 id = 1..N 连续唯一", "N=%d" % len(els))
    emit("通过" if not any(p.startswith("R2") for p in problems) else "不通过", "O2 先全部 text 后全部 icon",
         "text=%d icon=%d" % (n_text, n_icon))
    r3 = [p for p in problems if p.startswith("R3")]
    r4 = [p for p in problems if p.startswith("R4")]
    emit("通过" if not r3 else "不通过", "O3 组内「上→下」（%dpx 行桶，真实像素）" % ROW_BUCKET_PX,
         "无违例" if not r3 else "共 %d 处：%s" % (len(r3), " | ".join(r3[:3])))
    emit("通过" if not r4 else "不通过", "O4 同一行桶内「左→右」（真实像素 centerX）",
         "无违例" if not r4 else "共 %d 处：%s" % (len(r4), " | ".join(r4[:3])))
    emit("通过" if not facts["bad_px"] else "不通过", "O5 bbox_px == round(ratio*size)（±1px，真实像素交叉核对）",
         "全部一致" if not facts["bad_px"] else "%r" % facts["bad_px"][:5])

    if args.expect_icon_label:
        bad = [(e.get("id"), e.get("label")) for e in els if e.get("type") == "icon" and e.get("label") != args.expect_icon_label]
        emit("通过" if not bad else "不通过", "O6 icon 的 label 恒为 %r（该实例模式）" % args.expect_icon_label,
             "全部符合" if not bad else "不符合 %d 个：%r" % (len(bad), bad[:5]))

    emit("注意" if not facts["strict_y1x1_ok"] else "通过", "O7 附加信息：严格按 (y1, x1) 排序是否也成立",
         "成立（更严格）" if facts["strict_y1x1_ok"] else "不成立（30px 行桶读法的必然偏差，见下方同桶颠倒明细）")
    if facts["same_bucket_inverted"]:
        print("      同一 30px 行桶内、centerY 较大却排在前面（上→下在该桶内不严格）共 %d 对，前几对 (前id,cy,后id,cy)=%r"
              % (len(facts["same_bucket_inverted"]), facts["same_bucket_inverted"][:5]))

    print("      前 8 个元素（真实像素）：")
    for e in els[:8]:
        b = px_of(e, w, h)
        print("        id=%-4s %-4s [%7.1f,%7.1f,%7.1f,%7.1f] label=%r" % (e.get("id"), e.get("type"), b[0], b[1], b[2], b[3], str(e.get("label"))[:34]))
    print("      后 4 个元素（真实像素）：")
    for e in els[-4:]:
        b = px_of(e, w, h)
        print("        id=%-4s %-4s [%7.1f,%7.1f,%7.1f,%7.1f] label=%r" % (e.get("id"), e.get("type"), b[0], b[1], b[2], b[3], str(e.get("label"))[:34]))
    print("      全部行桶明细（供人工核对顺序）：")
    print_rows(els, w, h)

    n_fail = sum(1 for k, _, _ in RESULTS if k == "不通过")
    print("\n================ 顺序复核汇总 ================")
    print("通过 %d 项 / 不通过 %d 项（mode=%r）" % (sum(1 for k, _, _ in RESULTS if k == "通过"), n_fail, payload.get("mode")))
    if n_fail:
        print("不通过清单：")
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
