#!/usr/bin/env python3
"""A two-edit only patch to the ORIGINAL ColorCC installer for One UI 8.
Does not replace Hook.install, Hook$24, or any render/animation logic.
"""
import argparse, hashlib, json, re
from pathlib import Path
p=argparse.ArgumentParser()
p.add_argument('--decoded',required=True);p.add_argument('--report',required=True)
a=p.parse_args()
paths=list(Path(a.decoded).glob('smali*/dev/colorcc/Hook.smali'))
assert len(paths)==1,'Expected exactly one original Hook.smali'
path=paths[0];s=path.read_text()
start=s.index('.method private static install(Ljava/lang/ClassLoader;)V')
end=s.index('.end method',start)+len('.end method')
method=s[start:end]
needle='    invoke-static {v2, v5, v0}, Lde/robv/android/xposed/XposedHelpers;->findAndHookMethod(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Object;)Lde/robv/android/xposed/XC_MethodHook$Unhook;'
guard='    invoke-static {v2, v5, v0}, Ldev/colorcc/compat/SafeHooks;->findAndHookMethod(Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/Object;)Lde/robv/android/xposed/XC_MethodHook$Unhook;'
assert method.count(needle)==1
idx=method.index(needle)
assert 'const-string v5, "isInNonCollapsibleArea"' in method[max(idx-200,0):idx]
assert 'const-string v0, "onFinishInflate"' in method
assert 'new-instance p0, Ldev/colorcc/Hook$24;' in method
assert len(re.findall(r'^\s*move-result-object v3$',method,re.M))==1
# The verified v3 register retains the QSContainerImpl Class.
method=method.replace(needle,guard,1)
marker='    const-string p0, "hooks installed"'
assert method.count(marker)==1
extra='    invoke-static {v3}, Ldev/colorcc/OneUI8PanelFallback;->install(Ljava/lang/Class;)V\n\n'
method=method.replace(marker,extra+marker,1)
rebuilt=s[:start]+method+s[end:]
assert s[:start]==rebuilt[:start] and s[end:]==rebuilt[-len(s[end:]):]
# Validate only the two intentional changes.
assert rebuilt.replace(guard,needle,1).replace(extra,'',1)==s
path.write_text(rebuilt)
report={'original_sha256':hashlib.sha256(s.encode()).hexdigest(),'modified_sha256':hashlib.sha256(rebuilt.encode()).hexdigest(),'original_installer_retained':True,'guarded_method':'SecQSPanelComposeAdapter.isInNonCollapsibleArea(float,float)','added_fallback':'QSContainerImpl.onLayout afterHookedMethod, Samsung Android 16 only, <=2 attempts per view','original_onFinishInflate_hook_retained':True,'change_count':2,'runtime_verified':False}
Path(a.report).parent.mkdir(parents=True,exist_ok=True)
Path(a.report).write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
