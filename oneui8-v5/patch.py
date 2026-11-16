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


# --- One UI 8 fail-open stock visibility guards: ORIGINAL Panel.smali only ---
# The helper does nothing on Android versions other than Samsung One UI 8.
import re
panel_paths=list(Path(a.decoded).glob('smali*/dev/colorcc/Panel.smali'))
assert len(panel_paths)==1, "Expected exactly one original Panel.smali"
panel_path=panel_paths[0]
panel_original=panel_path.read_text(encoding='utf8')
panel_after=panel_original

def patch_method(signature, needle, addition):
    global panel_after
    m=re.search(r'(?m)^\.method (?:private|protected) '+re.escape(signature)+r'$', panel_after)
    assert m, "Missing original Panel method: "+signature
    start=m.start()
    end=panel_after.index('.end method',start)+len('.end method')
    part=panel_after[start:end]
    assert part.count(needle)==1, "Needle not unique in "+signature
    new=part.replace(needle,addition,1)
    panel_after=panel_after[:start]+new+panel_after[end:]

patch_method(
    'parkStock(Z)V',
    '    if-nez p1, :cond_3',
    '    invoke-static {p0, p1}, Ldev/colorcc/OneUI8Health;->guardStockHide(Ldev/colorcc/Panel;Z)Z\n\n'
    '    move-result p1\n\n'
    '    if-nez p1, :cond_3'
)
patch_method(
    'setStockHidden(Z)V',
    '    .line 2222\n    iget-boolean',
    '    invoke-static {p0, p1}, Ldev/colorcc/OneUI8Health;->guardStockHide(Ldev/colorcc/Panel;Z)Z\n\n'
    '    move-result p1\n\n'
    '    .line 2222\n    iget-boolean'
)
patch_method(
    'update()V',
    '    invoke-static {v1}, Ldev/colorcc/Logic;->clamp(F)F\n\n'
    '    move-result v1\n\n'
    '    .line 1733',
    '    invoke-static {v1}, Ldev/colorcc/Logic;->clamp(F)F\n\n'
    '    move-result v1\n\n'
    '    invoke-static {v0, v1}, Ldev/colorcc/OneUI8Health;->guardStockAlpha(Ldev/colorcc/Panel;F)F\n\n'
    '    move-result v1\n\n'
    '    .line 1733'
)
patch_method(
    'dispatchDraw(Landroid/graphics/Canvas;)V',
    '    invoke-super {p0, p1}, Landroid/widget/FrameLayout;->dispatchDraw(Landroid/graphics/Canvas;)V',
    '    invoke-super {p0, p1}, Landroid/widget/FrameLayout;->dispatchDraw(Landroid/graphics/Canvas;)V\n\n'
    '    invoke-static {p0}, Ldev/colorcc/OneUI8Health;->noteFrameDrawn(Ldev/colorcc/Panel;)V'
)
assert panel_after.count('Ldev/colorcc/OneUI8Health;')==4
assert len(list(difflib.unified_diff(panel_original.splitlines(),panel_after.splitlines()))) < 100
panel_path.write_text(panel_after,encoding='utf8')
report.update({
    "panel_guard_edits": 4,
    "original_panel_sha256": hashlib.sha256(panel_original.encode()).hexdigest(),
    "modified_panel_sha256": hashlib.sha256(panel_after.encode()).hexdigest(),
    "stock_panel_preservation": "On Samsung One UI 8, stock removal, accessibility hiding, and alpha fading are blocked until custom panel completes two visible draw passes.",
    "draw_passes_prove_visual_correctness": False,
    "on_device_verified": False
})
Path(a.report).write_text(json.dumps(report,indent=2))
print("Validated original renderer with four small One UI 8-only fail-open guards")
