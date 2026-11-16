#!/usr/bin/env python3
"""Minimal One UI 8 mitigation. Preserve original Hook.install body byte-for-byte
EXCEPT for its unguarded isInNonCollapsibleArea registration. Fail closed."""
from pathlib import Path
import argparse
import difflib
import hashlib
import json

p = argparse.ArgumentParser()
p.add_argument("--decoded", required=True)
p.add_argument("--report", required=True)
a = p.parse_args()
paths = list(Path(a.decoded).glob("smali*/dev/colorcc/Hook.smali"))
if len(paths) != 1:
    raise SystemExit("Missing or ambiguous original Hook.smali")
path = paths[0]
original = path.read_text(encoding="utf-8")
needle = ('    invoke-static {v2, v5, v0}, '
          'Lde/robv/android/xposed/XposedHelpers;->findAndHookMethod'
          '(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Object;)'
          'Lde/robv/android/xposed/XC_MethodHook$Unhook;')
replacement = needle.replace(
    'Lde/robv/android/xposed/XposedHelpers;->findAndHookMethod',
    'Ldev/colorcc/compat/SafeHooks;->findAndHookMethod'
)
if original.count(needle) != 1:
    raise SystemExit("Expected exactly one unguarded hook site, aborting")
at = original.index(needle)
previous = original.rfind("const-string v5, \"isInNonCollapsibleArea\"", 0, at)
if previous < 0 or at - previous > 650:
    raise SystemExit("Target is not the expected isInNonCollapsibleArea path")
if original.count('Ldev/colorcc/Hook$24;') < 1:
    raise SystemExit("Original panel attachment callback absent")
patched = original.replace(needle, replacement, 1)
assert len(patched) - len(original) == len(replacement) - len(needle)
path.write_text(patched, encoding="utf-8")
delta = list(difflib.unified_diff(original.splitlines(), patched.splitlines(), n=3))
if sum(x.startswith("-") and not x.startswith("---") for x in delta) != 1:
    raise SystemExit("Too many removed lines")
if sum(x.startswith("+") and not x.startswith("+++") for x in delta) != 1:
    raise SystemExit("Too many added lines")
report = {
    "change": "guard the original non-collapsible-area hook without replacing Hook.install",
    "original_sha256": hashlib.sha256(original.encode()).hexdigest(),
    "patched_sha256": hashlib.sha256(patched.encode()).hexdigest(),
    "smali_changed_lines": delta,
    "original_hook24_retained": True,
    "runtime_status": "untested",
}
Path(a.report).parent.mkdir(parents=True, exist_ok=True)
Path(a.report).write_text(json.dumps(report, indent=2))
print("Patched exactly one original hook registration; full installer retained")
