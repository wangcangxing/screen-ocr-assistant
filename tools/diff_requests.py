#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对比 mock 收到的前几次请求，定位去重键为什么会变。"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "mock-requests.log")

with open(LOG, encoding="utf-8") as f:
    raw = f.read()

blocks = [b for b in raw.split("=" * 70) if "USER CONTENT" in b]
print("共 %d 个请求\n" % len(blocks))

for i in range(1, min(len(blocks), 6)):
    a = blocks[i - 1].split("---- REPLY ----")[0].splitlines()
    b = blocks[i].split("---- REPLY ----")[0].splitlines()
    print("===== 请求 #%d 与 #%d 的差异 =====" % (i, i + 1))
    diffs = 0
    for j in range(max(len(a), len(b))):
        x = a[j] if j < len(a) else "<缺>"
        y = b[j] if j < len(b) else "<缺>"
        if x != y:
            diffs += 1
            print("  行%-3d A=%r" % (j, x))
            print("        B=%r" % (y,))
    if diffs == 0:
        print("  （完全相同）")
    print()
