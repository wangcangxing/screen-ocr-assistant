#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
本地 OpenAI 兼容模拟服务，仅用于验证 App 的整条链路。
- POST /v1/chat/completions  按 OpenAI 协议返回，答案由内置规则决定
- GET  /health               健康检查
每次请求会把收到的 user content 原样写到 mock-requests.log，便于核对 OCR 文字传得对不对。
"""
import base64
import json
import os
import re
import sys
import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "mock-requests.log")

# 对测试题库页 (quiz-test.html) 的固定答案。
# 关键词必须选 OCR 容错的片段：实测同一页面在不同机型上会认成
# 「水是由氢元素和氧元素组成的」/「水是由氢元素和氣元秦组成的」，用长句精确匹配必漏。
# 题干里的「水的说法」在两台设备上都稳定识别，故用它当判据。
RULES = [
    (["水的说法", "水分子中含有"], "A"),
    (["首都"], "B"),
    (["等于多少", "1 + 1", "1+1"], "C"),
]


def log_raw(user_content, answer):
    with open(LOG, "a", encoding="utf-8") as f:
        f.write("=" * 70 + "\n")
        f.write(datetime.datetime.now().isoformat(timespec="seconds") + "\n")
        f.write("---- USER CONTENT ----\n")
        f.write(user_content + "\n")
        f.write("---- REPLY ----\n")
        f.write(json.dumps(answer, ensure_ascii=False) + "\n")


def parse_options(user_content):
    """从【选项】段落里抽出 label -> text"""
    opts = {}
    m = re.search(r"【选项】\s*\n(.*?)(\n\s*\n|\Z)", user_content, re.S)
    if not m:
        return opts
    for line in m.group(1).splitlines():
        mm = re.match(r"^\s*([A-Ha-h1-8])\s*[.、．]\s*(.+?)\s*$", line)
        if mm:
            opts[mm.group(1).upper()] = mm.group(2)
    return opts


def decide_label(user_content):
    for kws, label in RULES:
        if any(k in user_content for k in kws):
            return label
    return None


def decide(user_content):
    label = decide_label(user_content)
    if label is None:
        return {
            "is_question": False,
            "answer_label": "",
            "answer_text": "",
            "confidence": 0.9,
            "explanation": "模拟服务未识别出题目",
        }
    opts = parse_options(user_content)
    # 优先用屏幕上真实识别到的选项原文，模拟真实模型「照着屏幕抄」的行为
    text = opts.get(label, "")
    return {
        "is_question": True,
        "answer_label": label,
        "answer_text": text,
        "confidence": 0.95,
        "explanation": "模拟服务按内置规则判定答案为 %s" % label,
    }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        print("[mock] GET %r" % self.path, flush=True)
        if self.path.startswith("/health"):
            self._send(200, {"ok": True})
            return
        if self.path.startswith("/v1/models") or self.path.startswith("/models"):
            # 字段按 DeepSeek GET /models 的 schema 造，用于验证 App 的模型选择逻辑
            self._send(200, {
                "object": "list",
                "data": [
                    {
                        "id": "mock-vision", "object": "model", "owned_by": "mock",
                        "name": "模拟多模态模型",
                        "context_window": 128000, "max_output_tokens": 8192,
                        "input_modalities": ["text", "image"], "output_modalities": ["text"],
                        "effort": {"supported_levels": ["low", "high", "max"], "default_level": "high"},
                    },
                    {
                        "id": "mock-text", "object": "model", "owned_by": "mock",
                        "name": "模拟纯文本模型",
                        "context_window": 64000, "max_output_tokens": 4096,
                        "input_modalities": ["text"], "output_modalities": ["text"],
                        "effort": {"supported_levels": ["low", "high"], "default_level": "high"},
                    },
                ],
            })
            return
        if self.path.startswith("/quiz"):
            html_path = os.path.join(HERE, "quiz-test.html")
            with open(html_path, "rb") as f:
                data = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        if self.path.startswith("/canvas"):
            # 视频式场景：题目画在 Canvas 上，无障碍节点树不变
            html_path = os.path.join(HERE, "canvas-quiz.html")
            with open(html_path, "rb") as f:
                data = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        self._send(404, {"error": "not found"})

    def do_POST(self):
        print("[mock] POST %r" % self.path, flush=True)
        n = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(n).decode("utf-8", "replace")
        try:
            req = json.loads(raw)
        except Exception as e:
            self._send(400, {"error": "bad json: %s" % e})
            return

        msgs = req.get("messages") or []
        user_content = ""
        system_content = ""
        image_info = "none"
        for m in msgs:
            if m.get("role") == "user":
                c = m.get("content")
                if isinstance(c, list):
                    # 块数组：取出文本块与图片块（验证 App 是否真的按视觉协议发图）
                    parts = []
                    for blk in c:
                        if not isinstance(blk, dict):
                            continue
                        if blk.get("type") == "text":
                            parts.append(blk.get("text", ""))
                        elif blk.get("type") == "image_url":
                            url = (blk.get("image_url") or {}).get("url", "")
                            detail = (blk.get("image_url") or {}).get("detail", "")
                            if url.startswith("data:image/"):
                                head, _, b64 = url.partition(",")
                                try:
                                    raw = base64.b64decode(b64)
                                    saved = os.path.join(HERE, "mock-received-image.jpg")
                                    with open(saved, "wb") as f:
                                        f.write(raw)
                                    # 是不是合法 JPEG：以 FFD8 开头
                                    ok = raw[:2] == b"\xff\xd8"
                                    image_info = ("mime=%s detail=%s base64=%dB decoded=%dB 合法JPEG=%s 已存=%s"
                                                  % (head.replace("data:", "").replace(";base64", ""),
                                                     detail, len(b64), len(raw), ok, os.path.basename(saved)))
                                except Exception as e:
                                    image_info = "解码失败: %s" % e
                            else:
                                image_info = "非 data:image URL: %s" % url[:60]
                    user_content = "\n".join(parts)
                else:
                    user_content = c or ""
            elif m.get("role") == "system":
                system_content = m.get("content", "") or ""
        auth = self.headers.get("Authorization", "")

        answer = decide(user_content)
        # 模拟真实模型：提示词要 JSON 就回 JSON，提示词要「只给选项」就回一个字母。
        # 设 MOCK_FORCE_JSON=1 可强制回 JSON，用来验证 App 的 JSON 兼容分支。
        wants_json = "JSON" in system_content.upper() or os.environ.get("MOCK_FORCE_JSON") == "1"
        if wants_json:
            content = json.dumps(answer, ensure_ascii=False)
            mode = "json"
        elif answer.get("is_question"):
            content = answer["answer_label"]
            mode = "plain"
        else:
            content = "NONE"
            mode = "plain"

        log_raw(
            "auth=%s mode=%s model=%s reasoning_effort=%s\nIMAGE: %s\nSYSTEM: %s\nUSER:\n%s"
            % (auth, mode, req.get("model"), req.get("reasoning_effort"), image_info,
               system_content[:200], user_content),
            {"reply": content})
        print("[mock] model=%s effort=%s mode=%s image=[%s] -> %s"
              % (req.get("model"), req.get("reasoning_effort"), mode, image_info,
                 content[:40].replace("\n", " ")),
              flush=True)

        self._send(200, {
            "id": "chatcmpl-mock",
            "object": "chat.completion",
            "created": 0,
            "model": req.get("model", "mock"),
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": content},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
        })

    def log_message(self, fmt, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    if os.path.exists(LOG):
        os.remove(LOG)
    srv = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print("[mock] listening on http://0.0.0.0:%d  (POST /v1/chat/completions)" % port, flush=True)
    srv.serve_forever()
