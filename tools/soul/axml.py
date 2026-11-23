import struct, sys, re
def u16(b,o): return struct.unpack_from('<H',b,o)[0]
def u32(b,o): return struct.unpack_from('<I',b,o)[0]
def s32(b,o): return struct.unpack_from('<i',b,o)[0]

def parse_pool(b, off):
    # returns list of strings; off = chunk start
    typ,hs,size = u16(b,off),u16(b,off+2),u32(b,off+4)
    n=u32(b,off+8); flags=u32(b,off+16); sstart=u32(b,off+20)
    utf8=bool(flags&0x100)
    offs=[u32(b,off+hs+4*i) for i in range(n)]
    out=[]
    base=off+sstart
    for o in offs:
        p=base+o
        try:
            if utf8:
                l=b[p]; p+=1
                if l&0x80: l=((l&0x7f)<<8)|b[p]; p+=1
                bl=b[p]; p+=1
                if bl&0x80: bl=((bl&0x7f)<<8)|b[p]; p+=1
                out.append(b[p:p+bl].decode('utf8','replace'))
            else:
                l=u16(b,p); p+=2
                if l&0x8000: l=((l&0x7fff)<<16)|u16(b,p); p+=2
                out.append(b[p:p+l*2].decode('utf-16le','replace'))
        except Exception: out.append('')
    return out, size

class Res:
    """resource table: id -> (type,name)"""
    def __init__(s): s.names={}; s.values={}  # id-> {config: (dtype,data)}; s.strpool=[]
RES=Res()

def fmt_dim(d):
    unit=['px','dp','sp','pt','in','mm'][d&0xf]
    r=(d>>4)&3; mant=(d>>8)
    if mant&0x800000: mant-=0x1000000
    radix=[23,16,8,0][r]
    v=mant/(1<<radix) if radix else mant
    v=round(v,2)
    if v==int(v): v=int(v)
    return f"{v}{unit}"
def fmt_frac(d):
    mant=(d>>8); 
    if mant&0x800000: mant-=0x1000000
    r=(d>>4)&3; radix=[23,16,8,0][r]
    v=round(mant/(1<<radix)*100,2) if radix else mant*100
    return f"{v}%"+('p' if d&0xf==1 else '')

def val(dtype,data,sp=None,raw=None):
    if dtype==3: return sp[data] if sp and data<len(sp) else (raw or '')
    if dtype==1: return rname(data)
    if dtype==2: return '?'+rname(data)[1:]
    if dtype==4: return str(struct.unpack('<f',struct.pack('<I',data))[0])
    if dtype==5: return fmt_dim(data)
    if dtype==6: return fmt_frac(data)
    if dtype==16: return str(s32(struct.pack('<I',data),0))
    if dtype==17: return hex(data)
    if dtype==18: return 'true' if data else 'false'
    if dtype in (28,29,26,27): return '#%08x'%data if dtype in(28,26) else '#%06x'%(data&0xffffff)
    return f"<t{dtype}:{data}>"
def rname(i):
    if i==0: return '@null'
    n=RES.names.get(i)
    if n: 
        pk='android:' if (i>>24)==1 else ''
        return f"@{pk}{n[0]}/{n[1]}"
    return '@0x%08x'%i

def axml(b):
    assert u16(b,0)==3
    pos=8; sp=[]; rmap=[]
    out=[]; depth=0; nsdecl=[]
    pending_ns=[]
    while pos<len(b):
        t,hs,sz=u16(b,pos),u16(b,pos+2),u32(b,pos+4)
        if t==1: sp,_=parse_pool(b,pos)
        elif t==0x180: rmap=[u32(b,pos+8+4*i) for i in range((sz-8)//4)]
        elif t==0x100:
            pfx,uri=u32(b,pos+16),u32(b,pos+20); pending_ns.append((sp[pfx],sp[uri]))
        elif t==0x102:
            ns,nm,astart,asz,ac=u32(b,pos+16),u32(b,pos+20),u16(b,pos+24),u16(b,pos+26),u16(b,pos+28)
            tag=sp[nm]; ind='    '*depth
            attrs=[]
            for p,u in pending_ns: attrs.append(f'xmlns:{p}="{u}"')
            pending_ns=[]
            ap=pos+16+astart
            for i in range(ac):
                a=ap+i*20
                ans,anm,araw=s32(b,a),u32(b,a+4),s32(b,a+8)
                dt=b[a+15]; dd=u32(b,a+16)
                name=sp[anm] if anm<len(sp) else '?'
                pre=''
                if ans>=0:
                    uri=sp[ans]
                    pre={'http://schemas.android.com/apk/res/android':'android:','http://schemas.android.com/apk/res-auto':'app:'}.get(uri,'x:')
                v=val(dt,dd,sp,sp[araw] if araw>=0 else None)
                v=v.replace('&','&amp;').replace('"','&quot;').replace('<','&lt;')
                attrs.append(f'{pre}{name}="{v}"')
            if len(attrs)>2 or sum(map(len,attrs))>80:
                out.append(f"{ind}<{tag}\n"+"\n".join(f"{ind}    {x}" for x in attrs)+">")
            else:
                out.append(f"{ind}<{tag}"+("".join(' '+x for x in attrs))+">")
            depth+=1
        elif t==0x103:
            depth-=1; nm=u32(b,pos+20); out.append('    '*depth+f"</{sp[nm]}>")
        pos+=sz
    # self-close empties
    txt="\n".join(out)
    txt=re.sub(r'>\n\s*</(\S+)>',lambda m:'/>' if True else '',txt) if False else txt
    return txt

def load_arsc(path):
    b=open(path,'rb').read()
    assert u16(b,0)==2
    pos=u32(b,0+2) if False else 12
    gpool,sz=parse_pool(b,12)
    RES.strpool=gpool
    pos=12+sz
    while pos<len(b):
        t,hs,sz=u16(b,pos),u16(b,pos+2),u32(b,pos+4)
        if t==0x200: parse_pkg(b,pos,gpool)
        pos+=sz
def parse_pkg(b,off,gpool):
    pid=u32(b,off+8)
    tsoff=u32(b,off+268); ksoff=u32(b,off+276)
    types,_=parse_pool(b,off+tsoff); keys,_=parse_pool(b,off+ksoff)
    pos=off+u16(b,off+2)
    # skip pools
    while pos<off+u32(b,off+4):
        t,hs,sz=u16(b,pos),u16(b,pos+2),u32(b,pos+4)
        if t==0x201:
            tid=b[pos+8]; cnt=u32(b,pos+12); estart=u32(b,pos+16)
            cfgsz=u32(b,pos+20)
            cfg=b[pos+20:pos+20+cfgsz]
            # describe config: locale/density/night
            density=u16(cfg,14) if cfgsz>=16 else 0
            lang=cfg[24:26].decode('latin1').strip('\0') if cfgsz>=28 else ''
            uimode=cfg[28] if cfgsz>=36 else 0  # rough
            sdk=u16(cfg,20) if cfgsz>=24 else 0
            cname=f"d{density}-{lang}-s{sdk}" if (density or lang or sdk) else 'default'
            idxo=pos+hs
            tflags=b[pos+9]
            ents=[]
            if tflags&1:
                for i in range(cnt):
                    idx=u16(b,idxo+4*i); eo=u16(b,idxo+4*i+2)*4; ents.append((idx,eo))
            elif tflags&2:
                for i in range(cnt):
                    eo=u16(b,idxo+2*i)
                    if eo!=0xFFFF: ents.append((i,eo*4))
            else:
                for i in range(cnt):
                    eo=u32(b,idxo+4*i)
                    if eo!=0xFFFFFFFF: ents.append((i,eo))
            for i,eo in ents:
                e=pos+estart+eo
                esz,fl=u16(b,e),u16(b,e+2)
                if fl&8:  # compact
                    key=esz; dt=(fl>>8)&0xff; dd=u32(b,e+4)
                    if key>=len(keys): continue
                    rid=(pid<<24)|(tid<<16)|i
                    RES.names[rid]=(types[tid-1],keys[key]); RES.values.setdefault(rid,{})[cname]=(dt,dd); continue
                key=u32(b,e+4)
                if key>=len(keys): continue
                rid=(pid<<24)|(tid<<16)|i
                RES.names[rid]=(types[tid-1],keys[key])
                if fl&1:
                    parent=u32(b,e+8); n=u32(b,e+12); items=[]
                    p=e+16
                    for _ in range(n):
                        nm=u32(b,p); dt=b[p+7]; dd=u32(b,p+8); items.append((nm,dt,dd)); p+=12
                    RES.values.setdefault(rid,{})[cname]=('map',parent,items)
                else:
                    dt=b[e+8+3]; dd=u32(b,e+8+4)
                    RES.values.setdefault(rid,{})[cname]=(dt,dd)
        pos+=sz

def rvalue(rid,cfg='default'):
    v=RES.values.get(rid,{})
    x=v.get(cfg) or (list(v.values())[0] if v else None)
    return x
