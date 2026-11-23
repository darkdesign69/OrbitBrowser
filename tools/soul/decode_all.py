import sys,os,glob
sys.path.insert(0,'/home/claude/tools')
import axml
S='/tmp/claude-0/-home-claude/27e1f639-1fbf-5db2-9645-23b70565c7fd/scratchpad/'
axml.load_arsc(S+'base/resources.arsc')
out=S+'soul_dec'; os.makedirs(out,exist_ok=True)
ok=bad=0
for f in glob.glob(S+'base/res/**/*',recursive=True):
    if os.path.isdir(f): continue
    rel=os.path.relpath(f,S+'base')
    d=os.path.join(out,rel); os.makedirs(os.path.dirname(d),exist_ok=True)
    b=open(f,'rb').read()
    if f.endswith('.xml') and b[:2]==b'\x03\x00':
        try: open(d,'w').write(axml.axml(b)); ok+=1
        except Exception as e: bad+=1; print('ERR',rel,e)
    else:
        open(d,'wb').write(b)
# values
import collections
lines=['<resources>']
for rid,v in sorted(axml.RES.values.items()):
    t,n=axml.RES.names[rid]
    if t in('string','color','dimen','integer','bool'):
        for cfg,x in v.items():
            if x[0]=='map': continue
            vv=axml.val(x[0],x[1],axml.RES.strpool)
            lines.append(f'  <{t} name="{n}" cfg="{cfg}">{vv}</{t}>')
open(out+'/values.txt','w').write('\n'.join(lines))
print(ok,bad)
