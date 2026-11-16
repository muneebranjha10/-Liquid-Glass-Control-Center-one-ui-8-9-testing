#!/usr/bin/env python3
"""Keep the ORIGINAL Hook.install() and add two small, verified One UI 8 changes."""
from pathlib import Path
import argparse, json, hashlib, re, difflib
parser=argparse.ArgumentParser()
parser.add_argument('--decoded', required=True)
parser.add_argument('--report',required=True)
a=parser.parse_args()
paths=list(Path(a.decoded).glob('smali*/dev/colorcc/Hook.smali'))
assert len(paths)==1, "Original Hook.smali must exist once"
path=paths[0]
original=path.read_text(encoding='utf8')
start=original.index('.method private static install(Ljava/lang/ClassLoader;)V')
end=original.index('.end method',start)+len('.end method')
method=original[start:end]
needle='    invoke-static {v2, v5, v0}, Lde/robv/android/xposed/XposedHelpers;->findAndHookMethod(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Object;)Lde/robv/android/xposed/XC_MethodHook$Unhook;'
guard=needle.replace('Lde/robv/android/xposed/XposedHelpers;', 'Ldev/colorcc/compat/SafeHooks;')
assert method.count(needle)==1, "Expected one unsafe registration"
idx=method.index(needle)
assert 'const-string v5, "isInNonCollapsibleArea"' in method[max(0,idx-240):idx]
assert 'new-instance p0, Ldev/colorcc/Hook$24;' in method
assert 'const-string v0, "onFinishInflate"' in method
method=method.replace(needle,guard,1)
marker='    const-string p0, "hooks installed"'
assert method.count(marker)==1
extra='    invoke-static {v3}, Ldev/colorcc/OneUI8PanelFallback;->install(Ljava/lang/Class;)V\n\n'
method=method.replace(marker, extra+marker,1)
patched=original[:start]+method+original[end:]
assert patched.replace(guard,needle,1).replace(extra,'',1)==original
path.write_text(patched,encoding='utf8')
report={
 'original_apk_used': 'input/Combine-CC-Geometry-Experimental.apk',
 'original_sha256':hashlib.sha256(original.encode()).hexdigest(),
 'patched_sha256':hashlib.sha256(patched.encode()).hexdigest(),
 'changes':['Guard original absent isInNonCollapsibleArea hook',
            'Observe verified legacy and Compose QS paths only on Samsung One UI 8'],
 'original_hook24_onFinishInflate':'unchanged',
 'original_panel_renderer_and_callbacks':'unchanged',
 'stock_panel_suppression_code':'unchanged',
 'runtime_verification':'NOT TESTED',
 'diff':list(difflib.unified_diff(original.splitlines(),patched.splitlines(),n=2))
 }
Path(a.report).parent.mkdir(parents=True,exist_ok=True)
Path(a.report).write_text(json.dumps(report,indent=2))
print('Validated original installer preserved with exactly two changes')
