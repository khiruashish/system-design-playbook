import re,glob,os,html
from collections import Counter,defaultdict
docs='docs'; issues=defaultdict(list); AP=[]
files=sorted(glob.glob(docs+'/*/[0-9][0-9]-*.md'))
for f in files:
    n=int(os.path.basename(f)[:2]); t=open(f).read(); name=os.path.basename(f)[:2]
    if n==0: continue
    for s in range(1,10):
        if not re.search(r'^## %d\. '%s,t,re.M): issues[name].append('missing ## %d.'%s)
    qs=re.findall(r'^\?\?\? question "Q(\d+) \[(B\d)\]',t,re.M)
    nums=[int(a) for a,_ in qs]
    if nums!=list(range(1,21)): issues[name].append('Q numbering %s'%nums)
    for a,b in qs:
        a=int(a); ok={True: True}
        exp=['B1','B2'] if a<=4 else ['B2'] if a<=9 else ['B3'] if a<=14 else ['B4']
        if b not in exp: issues[name].append('Q%d band %s (expected %s)'%(a,b,'/'.join(exp)))
    mm=re.findall(r'```mermaid\n(.*?)```',t,re.S)
    if len(mm)<4: issues[name].append('only %d mermaid'%len(mm))
    if t.count('sequenceDiagram')<2: issues[name].append('%d sequence diagrams (need happy + failure)'%t.count('sequenceDiagram'))
    if 'stateDiagram' not in t: issues[name].append('no state diagram')
    sec=lambda k: (re.search(r'^## %d\..*?(?=^## %d\.)'%(k,k+1),t,re.M|re.S) or re.search(r'^## %d\..*'%k,t,re.M|re.S)).group(0) if re.search(r'^## %d\.'%k,t,re.M) else ''
    s4=sec(4)
    if not (re.search(r'R1',s4) or re.search(r'\| ID \|',s4) or re.search(r'NFR|FR-?1|Requirements',s4)): issues[name].append('design doc lacks R1/table')
    s7=sec(7)
    d=len(re.findall(r'^!!! danger',s7,re.M))+len(re.findall(r'^\*\*\d+\.',s7,re.M))+len(re.findall(r'^### \d+\.',s7,re.M))
    AP.append((name,d))
    if d<8: issues[name].append('%d anti-patterns'%d)
    for w in ['TODO','TBD','FIXME','XXX','Coming soon','coming soon','lorem']:
        for m in re.finditer(r'\b'+w+r'\b',t):
            line=t[:m.start()].count('\n')+1; ctx=t[max(0,m.start()-40):m.end()+40].replace('\n',' ')
            issues[name].append('marker "%s" line %d: …%s…'%(w,line,ctx))
    for m in re.finditer(r'^(\?\?\? question[^\n]*)\n\n(?!    )',t,re.M): issues[name].append('question with unindented body')
# anchors: use built site
ids={}
for h in glob.glob('site/**/*.html',recursive=True):
    ids[os.path.normpath(h)]=set(re.findall(r'\bid="([^"]+)"',open(h,encoding='utf-8').read()))
bad=Counter()
for h in ids:
    src=open(h,encoding='utf-8').read()
    for href in re.findall(r'href="([^"#:]*)#([^"]+)"',src):
        path,frag=href
        tgt=os.path.normpath(os.path.join(os.path.dirname(h),path)) if path else h
        if os.path.isdir(tgt): tgt=os.path.join(tgt,'index.html')
        if tgt in ids and html.unescape(frag) not in ids[tgt] and not frag.startswith('__'):
            bad[(h.replace('site/',''),path+'#'+frag)]+=1
for k,v in issues.items():
    for i in v: print(k,i)
print('chapters with issues:',len(issues))
print('anti-pattern counts:',Counter(d for _,d in AP))
print('broken anchors:',len(bad))
for (h,l),c in list(bad.items())[:40]: print('  ',h,'->',l)
