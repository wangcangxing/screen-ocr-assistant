#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
用 mock 收到的两次请求，按 App 里同一套算法重建 questionKey 并计算相似度，
看看到底差多少、为什么没触发 0.85 的阈值。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "mock-requests.log")

sys.stdout.reconfigure(encoding="utf-8")


def longest_common_substring(a, b):
    best = 0
    prev = [0] * (len(b) + 1)
    for i in range(1, len(a) + 1):
        cur = [0] * (len(b) + 1)
        for j in range(1, len(b) + 1):
            cur[j] = prev[j - 1] + 1 if a[i - 1] == b[j - 1] else 0
            if cur[j] > best:
                best = cur[j]
        prev = cur
    return best


def similarity(a, b):
    if not a or not b:
        return 0.0
    return longest_common_substring(a, b) / min(len(a), len(b))


def section(user_content, name):
    m = re.search(r"【%s】\s*\n(.*?)(?:\n\s*\n|\Z)" % name, user_content, re.S)
    return m.group(1) if m else ""


def levenshtein(a, b):
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cost = 0 if a[i - 1] == b[j - 1] else 1
            cur[j] = min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
        prev = cur
    return prev[len(b)]


def edit_similarity(a, b):
    if not a or not b:
        return 0.0
    if a == b:
        return 1.0
    return 1.0 - levenshtein(a, b) / max(len(a), len(b))


def question_key(user_content):
    """与 Kotlin 侧 questionKey 同一套算法：题干(去空白,取末200) + 排序后的 选项label:text"""
    stem = re.sub(r"\s+", "", section(user_content, "题干"))[-200:]
    opts = options_of(user_content)
    key = (stem + "|" + "|".join(opts))[:400]
    return key, stem, opts


def options_of(user_content):
    opts = []
    for line in section(user_content, "选项").splitlines():
        m = re.match(r"^\s*([A-Ha-h1-8])\s*[.、．]\s*(.+?)\s*$", line)
        if m:
            opts.append(re.sub(r"\s+", "", m.group(1).upper() + ":" + m.group(2)))
    opts.sort()
    return opts


with open(LOG, encoding="utf-8") as f:
    raw = f.read()

blocks = [b for b in raw.split("=" * 70) if "USER CONTENT" in b]
print("共 %d 个请求\n" % len(blocks))

keys, opt_keys = [], []
for i, b in enumerate(blocks):
    body = b.split("---- REPLY ----")[0]
    content = body.split("USER:", 1)[1] if "USER:" in body else body
    k, stem, opts = question_key(content)
    keys.append(k)
    opt_keys.append("|".join(opts))
    print("请求 #%d  整键长=%d  仅选项键长=%d" % (i + 1, len(k), len(opt_keys[-1])))

print()
print("=" * 72)
print("%-14s %-12s %-12s" % ("请求对", "整键相似度", "仅选项相似度"))
for i in range(1, len(keys)):
    s_all = edit_similarity(keys[i - 1], keys[i])
    s_opt = edit_similarity(opt_keys[i - 1], opt_keys[i])
    print("#%-13s %-12.4f %-12.4f" % ("%d-%d" % (i, i + 1), s_all, s_opt))
