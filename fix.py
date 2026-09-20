import sys,re,os,json,urllib.request
def simple_fix(fp):
    if not os.path.isfile(fp): return False
    L=open(fp,encoding='utf-8').read().split('\n'); c=False
    n=[];p=None
    for l in L:
        s=l.strip()
        if s.startswith('import '):
            if s==p: c=True; continue
            p=s
        else: p=None
        n.append(l)
    L=n;n=[];i=0
    while i<len(L):
        s=L[i].strip()
        if s in('@Composable','@OptIn','@Preview') and i+1<len(L):
            ns=L[i+1].strip()
            if not(ns.startswith('fun ')or ns.startswith('private fun ')or ns.startswith('class ')or ns.startswith('@')or ns==''or ns.startswith('override ')):
                c=True;i+=1;continue
        n.append(L[i]);i+=1
    L=n;t='\n'.join(L)
    if t.count('{')-t.count('}')==1: L.append('}');c=True
    elif t.count('}')-t.count('{')==1:
        for j in range(len(L)-1,-1,-1):
            if L[j].strip()=='}': del L[j];c=True;break
    if c: open(fp,'w',encoding='utf-8').write('\n'.join(L));return True
    return False
def ai_fix(fp,log):
    k=os.environ.get("GEMINI_API_KEY","")
    if not k: return False
    fc=open(fp,encoding='utf-8').read()
    pr="You are an expert Kotlin/Android developer. Fix ALL compilation errors. Error:\n"+log[-3000:]+"\n\nFile "+fp+":\n```kotlin\n"+fc+"\n```\nReturn ONLY the complete corrected file in ```kotlin ... ```. No explanation."
    u="https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key="+k
    r=urllib.request.Request(u,data=json.dumps({"contents":[{"parts":[{"text":pr}]}]}).encode(),headers={"Content-Type":"application/json"})
    try:
        d=json.loads(urllib.request.urlopen(r,timeout=120).read())
        c=d["candidates"][0]["content"]["parts"][0]["text"]
        m=re.search(r"```kotlin\s*(.*?)\s*```",c,re.DOTALL)
        fx=m.group(1) if m else c
        if fx.count("{")!=fx.count("}") or fx.count("(")!=fx.count(")"): return False
        open(fp,'w',encoding='utf-8').write(fx);return True
    except: return False
log=open(sys.argv[1],encoding='utf-8',errors='replace').read()
fs=list(dict.fromkeys(re.findall(r'([a-zA-Z0-9_./-]+\.kt):\d+',log)))
if not fs: sys.exit(1)
for f in fs[:2]:
    if simple_fix(f): print("simple-fixed:",f); sys.exit(0)
if ai_fix(fs[0],log): print("AI-fixed:",fs[0]); sys.exit(0)
print("no fix applicable"); sys.exit(1)
