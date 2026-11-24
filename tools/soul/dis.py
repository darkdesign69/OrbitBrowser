# Minimal Dalvik disassembler for reading Soul's method logic. usage: dis.py DEXDIR CLASS_SUBSTR [METHOD_SUBSTR]
import sys,glob,struct
from dexr import *
F={}
def fm(ops,f,k=None):
    for o in ops: F[o]=(f,k)
fm([0x00],'10x');fm([1,4,7],'12x');fm([2,5,8],'22x');fm([3,6,9],'32x')
fm([0xa,0xb,0xc,0xd,0xf,0x10,0x11,0x1d,0x1e,0x27],'11x');fm([0xe],'10x')
fm([0x12],'11n');fm([0x13,0x16],'21s');fm([0x14,0x17],'31i');fm([0x15,0x19],'21h');fm([0x18],'51l')
fm([0x1a],'21c','s');fm([0x1b],'31c','s');fm([0x1c,0x1f,0x22],'21c','t');fm([0x20,0x23],'22c','t')
fm([0x21],'12x');fm([0x24],'35c','t');fm([0x25],'3rc','t');fm([0x26,0x2b,0x2c],'31t')
fm([0x28],'10t');fm([0x29],'20t');fm([0x2a],'30t');fm(range(0x2d,0x32),'23x')
fm(range(0x32,0x38),'22t');fm(range(0x38,0x3e),'21t');fm(range(0x44,0x52),'23x')
fm(range(0x52,0x60),'22c','f');fm(range(0x60,0x6e),'21c','f')
fm(range(0x6e,0x73),'35c','m');fm(range(0x74,0x79),'3rc','m')
fm(range(0x7b,0x90),'12x');fm(range(0x90,0xb0),'23x');fm(range(0xb0,0xd0),'12x')
fm(range(0xd0,0xd8),'22s');fm(range(0xd8,0xe3),'22b')
fm([0xfa],'45cc','m');fm([0xfb],'4rc','m');fm([0xfc],'35c');fm([0xfd],'3rc');fm([0xfe,0xff],'21c')
L={'10x':1,'12x':1,'11n':1,'11x':1,'10t':1,'20t':2,'22x':2,'21t':2,'21s':2,'21h':2,'21c':2,'23x':2,'22b':2,'22t':2,'22s':2,'22c':2,'30t':3,'32x':3,'31i':3,'31t':3,'31c':3,'35c':3,'3rc':3,'45cc':4,'4rc':4,'51l':5}
N={0xe:'return-void',0xf:'return',0x11:'return-obj',0xc:'move-result-obj',0xa:'move-result',0x1a:'const-str',0x1b:'const-str',0x1c:'const-class',0x22:'new',0x12:'const',0x13:'const',0x14:'const',0x15:'const-hi',0x1f:'cast',0x20:'instanceof',0x28:'goto',0x2b:'pswitch',0x2c:'sswitch',0x27:'throw'}
for i,n in enumerate(['eq','ne','lt','ge','gt','le']): N[0x32+i]='if-'+n; N[0x38+i]='if-'+n+'z'
for i,n in enumerate(['','-wide','-obj','-bool','-byte','-char','-short']):
    N[0x44+i]='aget'+n; N[0x4b+i]='aput'+n; N[0x52+i]='iget'+n; N[0x59+i]='iput'+n; N[0x60+i]='sget'+n; N[0x67+i]='sput'+n
for i,n in enumerate(['virt','super','direct','static','iface']): N[0x6e+i]='call-'+n; N[0x74+i]='call-'+n+'/r'
def sh(t): return t.rsplit('/',1)[-1].rstrip(';') if t.startswith('L') else t
def dis(d,co):
    b=d.b; n=u32(b,co+12); base=co+16
    u=lambda i:u16(b,base+2*i)
    s=lambda i:struct.unpack_from('<h',b,base+2*i)[0]
    out=[];i=0
    while i<n:
        w=u(i);op=w&0xff;a=w>>8
        if op==0 and w in(0x100,0x200,0x300):
            sz=u(i+1)
            ln={0x100:sz*2+4,0x200:sz*4+2,0x300:(sz*u(i+1) if 0 else 0)}[w] if w!=0x300 else ((u(i+2)|u(i+3)<<16)*sz+1)//2+4
            out.append((i,'payload')); i+=ln; continue
        f,k=F.get(op,('10x',None)); ln=L[f]; t=N.get(op,'op%02x'%op); x=''
        if f=='21c' or f=='31c':
            idx=u(i+1) if f=='21c' else u(i+1)|u(i+2)<<16
            if k=='s': x='v%d "%s"'%(a,d.string(idx).replace('\n','\\n')[:80])
            elif k=='t': x='v%d %s'%(a,sh(d.type(idx)))
            elif k=='f': c,nm=d.field(idx); x='v%d %s.%s'%(a,sh(c),nm)
        elif f=='22c':
            idx=u(i+1)
            if k=='f': c,nm=d.field(idx); x='v%d,v%d %s.%s'%(a&15,a>>4,sh(c),nm)
            else: x='v%d,v%d %s'%(a&15,a>>4,sh(d.type(idx)))
        elif f in('35c','3rc'):
            idx=u(i+1); r=u(i+2)
            if f=='35c':
                cnt=a>>4; regs=[r&15,(r>>4)&15,(r>>8)&15,(r>>12)&15,a&15][:cnt]
                rs=','.join('v%d'%q for q in regs)
            else: rs='v%d..v%d'%(r,r+a-1)
            if k=='m': c,nm=d.meth(idx); x='%s.%s(%s)'%(sh(c),nm,rs)
            else: x=rs+' '+sh(d.type(idx)) if k=='t' else rs
        elif f=='11n': v=a>>4; v=v-16 if v>7 else v; x='v%d,%d'%(a&15,v)
        elif f=='21s': x='v%d,%d'%(a,s(i+1))
        elif f=='21h': x='v%d,%#x0000'%(a,u(i+1))
        elif f=='31i': x='v%d,%d'%(a,struct.unpack_from('<i',b,base+2*i+2)[0])
        elif f=='21t': x='v%d,%+d'%(a,s(i+1))
        elif f=='22t': x='v%d,v%d,%+d'%(a&15,a>>4,s(i+1))
        elif f=='10t': x='%+d'%((a-256) if a>127 else a)
        elif f=='11x': x='v%d'%a
        elif f=='12x': x='v%d,v%d'%(a&15,a>>4)
        elif f=='22b': x='v%d,v%d,%d'%(a,u(i+1)&255,struct.unpack_from('<b',b,base+2*i+3)[0])
        elif f=='23x': x='v%d,v%d,v%d'%(a,u(i+1)&255,u(i+1)>>8)
        elif f=='22s': x='v%d,v%d,%d'%(a&15,a>>4,s(i+1))
        elif f=='22x': x='v%d,v%d'%(a,u(i+1))
        elif f=='20t': x='%+d'%s(i+1)
        out.append((i,t+' '+x)); i+=ln
    return out
def run(dexdir,cls,meth=None):
    for p in sorted(glob.glob(dexdir+'/classes*.dex')):
        d=Dex(p); fn=lambda t:t
        for name,sup,cdo in d.classes():
            if cls not in name: continue
            print('==',name,'extends',sup,'(%s)'%p.rsplit('/',1)[-1])
            for idx,co in d.methods(cdo):
                c,mn=d.meth(idx)
                if meth and meth not in mn: continue
                print(' --',mn)
                if co:
                    for off,t in dis(d,co): print('   %04x %s'%(off,t))
if __name__=='__main__': run(sys.argv[1],sys.argv[2],sys.argv[3] if len(sys.argv)>3 else None)
