import sys,glob,struct,collections,pickle
sys.path.insert(0,'/home/claude/tools')
import dexr
S='/tmp/claude-0/-home-claude/27e1f639-1fbf-5db2-9645-23b70565c7fd/scratchpad/'
res=collections.defaultdict(list)
for f in sorted(glob.glob(S+'base/classes*.dex')):
    d=dexr.Dex(f)
    fcache={}
    def fld(i):
        if i not in fcache: fcache[i]=d.field(i)
        return fcache[i]
    for c,sup,cdo in d.classes():
        if not c.startswith('Lcom/mycompany/app/'): continue
        for midx,co in d.methods(cdo):
            code=d.code(co); seq=[]
            for i in range(0,len(code)-3,2):
                op=code[i]
                if 0x60<=op<=0x6d:
                    fi=struct.unpack_from('<H',code,i+2)[0]
                    if fi<d.nfield:
                        cl,nm=fld(fi)
                        if '/R$' in cl: seq.append(('R',cl.split('$')[1].rstrip(';'),nm))
                        elif cl.startswith('Lcom/mycompany/app/pref/'): seq.append(('P',cl.split('/')[-1].rstrip(';'),nm))
                elif op==0x1a:
                    si=struct.unpack_from('<H',code,i+2)[0]
                    if si<d.nstr and code[i+1]<0x10: 
                        try: seq.append(('S',d.string(si)))
                        except: pass
            if seq: res[c.split('$')[0]].append((c,d.meth(midx)[1],seq))
pickle.dump(dict(res),open(S+'cls_seq.pkl','wb'))
print(len(res))
