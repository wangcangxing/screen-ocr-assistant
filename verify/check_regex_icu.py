#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
ICU 兼容性静态检查（可复用检查项）· 针对 Android 的 java.util.regex
=====================================================================
**为什么必须有这个检查（验证方法学缺陷）**：
宿主机 JDK 的 `java.util.regex` 支持 Java 专有内联标志（如 `(?U)`），而 **Android 的 `java.util.regex` 底层是 ICU**，
不支持 `(?U)` —— 真机实测铁证（Lead，最终 APK `3C47786A…`）：

    java.util.regex.PatternSyntaxException: Syntax error in regexp pattern near index 3
    (?U)^[\\s0-9０-９…]*$
    at ...ScreenOcrAccessibilityService.<init>(ScreenOcrAccessibilityService.kt:179)

即：**「逐字复制源码 → 在宿主机 JDK 上跑差分」会绿，但真机启动即崩**。
所以凡涉及正则/Unicode 的判据，除了 JVM 差分，还必须跑本检查（静态），最终仍以真机启动验证为准。

检查规则（severity 说明）：
  P0  高：Java 专有内联标志，Android/ICU 不支持 → 实测/高度可能 PatternSyntaxException（`(?U)` 已有真机铁证）
  P1  中：Java 专有的 Unicode 属性别名形态（`\\p{IsXxx}` / `\\p{javaXxx}`）→ ICU 属性名不同，需改 `\\p{Xxx}` 或改用显式码点集合
  P2  需人工确认：ICU 支持但语义可能不同的构造（`(?i)(?m)(?s)(?x)`、`(?<name>`、`(?>`、`\\p{...}`、`\\Q\\E`）
  INFO 无法静态判定：`Regex(<非字面量>)`（变量/拼接）→ 需人工看

用法：
  python verify\\check_regex_icu.py                     # 默认扫 app/ 下 *.kt/*.java
  python verify\\check_regex_icu.py --root app
  python verify\\check_regex_icu.py --selftest          # 自检检查器本身

退出码：0 = 无 P0；1 = 发现 P0；2 = 用法错误。
"""

import argparse
import io
import os
import re
import sys

ROOTS_DEFAULT = ["app"]
EXTS = (".kt", ".java")

# 触发点：这些调用之后的第一个字符串字面量就是「交给正则引擎的 pattern」
CALL_SITES = ("Regex(", "Pattern.compile(", ".toRegex(")

# (severity, 名称, 正则, 说明)
RULES = [
    ("P0", "(?U) UNICODE_CHARACTER_CLASS", re.compile(r"\(\?U"),
     "Java 专有内联标志；Android/ICU 不支持 → 真机实测 PatternSyntaxException（本仓库已验证过一次崩溃）"),
    ("P0", "(?d) UNIX_LINES", re.compile(r"\(\?d"),
     "Java 专有内联标志；ICU 不支持"),
    ("P0", "(?u) UNICODE_CASE", re.compile(r"\(\?u"),
     "Java 专有内联标志；ICU 不支持（注意与 (?U) 大小写不同）"),
    ("P1", "\\p{IsXxx} Java 属性别名", re.compile(r"\\p\{Is"),
     "Java 用 Is 前缀；ICU 一般写作 \\p{Xxx}（如 \\p{White_Space}），需真机确认"),
    ("P1", "\\p{javaXxx} Java 属性", re.compile(r"\\p\{java"),
     "Java 专有属性名，ICU 不认"),
    ("P2", "\\p{...} Unicode 属性", re.compile(r"\\[pP]\{"),
     "ICU 支持属性，但名称集与 Java 不同，需真机确认"),
    ("P2", "命名捕获组 (?<name>", re.compile(r"\(\?<[A-Za-z]"),
     "ICU 支持命名组，但语义/兼容性需确认（且与 lookbehind (?<=…)/(?<!…) 形态不同）"),
    ("P2", "原子组 (?>", re.compile(r"\(\?>"),
     "ICU 支持情况随版本变化，需真机确认"),
    ("P2", "内联标志 (?i)(?m)(?s)(?x)", re.compile(r"\(\?[imsx]"),
     "ICU 通常支持，但建议真机确认（Android 不同版本行为有差异）"),
    ("P2", "\\Q...\\E 引用", re.compile(r"\\Q"),
     "ICU 支持，仍建议确认真机行为"),
]


def find_literals(text):
    """返回 [(index, literal, kind)]，kind ∈ escaped/raw/non-literal。"""
    out = []
    for site in CALL_SITES:
        start = 0
        while True:
            i = text.find(site, start)
            if i < 0:
                break
            start = i + len(site)
            j = i + len(site)
            while j < len(text) and text[j] in " \t\r\n":
                j += 1
            if text.startswith('"""', j):
                k = text.find('"""', j + 3)
                if k < 0:
                    out.append((i, None, "unterminated"))
                else:
                    out.append((i, text[j + 3:k], "raw"))
            elif j < len(text) and text[j] == '"':
                buf = []
                k = j + 1
                term = False
                while k < len(text):
                    c = text[k]
                    if c == "\\":
                        buf.append(text[k:k + 2])
                        k += 2
                        continue
                    if c == '"':
                        term = True
                        break
                    if c == "\n":
                        break
                    buf.append(c)
                    k += 1
                out.append((i, "".join(buf) if term else None, "escaped" if term else "unterminated"))
            else:
                out.append((i, None, "non-literal"))
    return out


def decode(literal, kind):
    if literal is None:
        return None
    # Kotlin 转义字符串里 `\\s` 在源码中写作两个反斜杠；正则引擎看到的是一个
    return literal.replace("\\\\", "\\") if kind == "escaped" else literal


def scan_file(path):
    text = io.open(path, encoding="utf-8", errors="replace").read()
    findings = []
    for idx, literal, kind in find_literals(text):
        line = text.count("\n", 0, idx) + 1
        if kind == "non-literal":
            findings.append(("INFO", "Regex(非字面量)", line, "(变量/拼接)",
                             "无法静态判定；若该 pattern 来自运行时拼接，请人工确认不含 Java 专有构造"))
            continue
        if literal is None:
            findings.append(("INFO", "字符串未闭合", line, "(解析失败)",
                             "提取字面量失败，请人工查看"))
            continue
        dec = decode(literal, kind)
        for sev, name, rx, why in RULES:
            m = rx.search(dec)
            if m:
                findings.append((sev, name, line, m.group(0), why))
    return findings


def run_selftest():
    cases = [
        ('private val R = Regex("(?U)^[\\\\s0-9]*$")', "P0", "(?U)"),
        ('val R = Regex("^[\\\\p{IsWhite_Space}]*$")', "P1", "\\p{Is"),
        ('val R = Regex("(?<year>\\\\d{4})")', "P2", "命名捕获组"),
        ('val R = Regex("(?i)abc")', "P2", "内联标志"),
        ('val R = Regex("^[a-z0-9]*$")', None, "纯 ASCII 正则不应误报"),
        ('val R = Regex("^[\\\\s\\\\u3000]*$")', None, "显式码点集合不应误报"),
        ('val R = Regex(pat)', "INFO", "非字面量"),
    ]
    fails = 0
    for src, expect_sev, name in cases:
        os.makedirs(os.path.join(os.path.dirname(os.path.abspath(__file__)), "out"), exist_ok=True)
        tmp = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out", "_icu_selftest.kt")
        io.open(tmp, "w", encoding="utf-8").write("// selftest\n" + src + "\n")
        found = scan_file(tmp)
        sevs = [f[0] for f in found]
        ok = (expect_sev in sevs) if expect_sev else (not [s for s in sevs if s in ("P0", "P1")])
        if not ok:
            fails += 1
        print("[%s] 自检·%s → 检出 %r（期望 %s）" % ("通过" if ok else "不通过", name, sevs, expect_sev or "无 P0/P1"))
    print("\n自检结论：%s" % ("通过" if fails == 0 else "不通过（%d 项）" % fails))
    return 0 if fails == 0 else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", action="append", default=[])
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return run_selftest()

    here = os.path.dirname(os.path.abspath(__file__))
    workspace = os.path.dirname(here)
    roots = args.root or ROOTS_DEFAULT

    all_findings = []
    scanned = 0
    for r in roots:
        base = r if os.path.isabs(r) else os.path.join(workspace, r)
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [d for d in dirnames if d not in (".git", "build", ".gradle", ".idea")]
            for fn in filenames:
                if not fn.endswith(EXTS):
                    continue
                p = os.path.join(dirpath, fn)
                scanned += 1
                for sev, name, line, snippet, why in scan_file(p):
                    all_findings.append((sev, os.path.relpath(p, workspace), line, name, snippet, why))

    print("ICU 兼容性静态检查 · 扫描 %d 个文件（roots=%s）" % (scanned, ",".join(roots)))
    order = {"P0": 0, "P1": 1, "P2": 2, "INFO": 3}
    all_findings.sort(key=lambda f: (order.get(f[0], 9), f[1], f[2]))
    for sev, path, line, name, snippet, why in all_findings:
        print("[%s] %s:%d  %s  片段=%r" % (sev, path, line, name, snippet))
        print("      → %s" % why)
    p0 = [f for f in all_findings if f[0] == "P0"]
    p1 = [f for f in all_findings if f[0] == "P1"]
    print("\n汇总：P0=%d  P1=%d  其余=%d" % (len(p0), len(p1), len(all_findings) - len(p0) - len(p1)))
    if p0:
        print("结论：**不通过** —— 存在 Android/ICU 不支持的 Java 专有正则构造，真机可能启动即崩（已有先例）。")
        return 1
    if p1:
        print("结论：**注意** —— 无 P0，但有 Java 专有属性别名，建议真机确认。")
        return 0
    print("结论：通过（无 P0/P1；P2/INFO 项建议真机确认）。")
    return 0


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    sys.exit(main())
