import struct,sys
def u16(b,o): return struct.unpack_from('<H',b,o)[0]
def u32(b,o): return struct.unpack_from('<I',b,o)[0]
def uleb(b,p):
    r=0;s=0
    while True:
        c=b[p];p+=1;r|=(c&0x7f)<<s;s+=7
        if not c&0x80:break
    return r,p
def mutf(b,p):
    _,p=uleb(b,p); e=b.index(0,p); return b[p:e].decode('utf8','replace')
class Dex:
    def __init__(s,path):
        b=s.b=open(path,'rb').read()
        s.nstr=u32(b,56); s.stroff=u32(b,60); s.ntype=u32(b,64); s.typeoff=u32(b,68)
        s.nproto=u32(b,72); s.protooff=u32(b,76); s.nfield=u32(b,80); s.fieldoff=u32(b,84)
        s.nmeth=u32(b,88); s.methoff=u32(b,92); s.ncls=u32(b,96); s.clsoff=u32(b,100)
        s._str={}
    def string(s,i):
        if i not in s._str: s._str[i]=mutf(s.b,u32(s.b,s.stroff+4*i))
        return s._str[i]
    def type(s,i): return s.string(u32(s.b,s.typeoff+4*i))
    def meth(s,i):
        o=s.methoff+8*i; return s.type(u16(s.b,o)), s.string(u32(s.b,o+4))
    def field(s,i):
        o=s.fieldoff+8*i; return s.type(u16(s.b,o)), s.string(u32(s.b,o+4))
    def classes(s):
        b=s.b
        for i in range(s.ncls):
            o=s.clsoff+32*i
            cidx=u32(b,o); acc=u32(b,o+4); sup=u32(b,o+8); cdo=u32(b,o+24)
            yield s.type(cidx), (s.type(sup) if sup!=0xffffffff else None), cdo
    def methods(s,cdo):
        b=s.b
        if not cdo: return
        p=cdo
        sf,p=uleb(b,p); inf,p=uleb(b,p); dm,p=uleb(b,p); vm,p=uleb(b,p)
        for _ in range(sf+inf): _,p=uleb(b,p); _,p=uleb(b,p)
        for cnt in (dm,vm):
            idx=0
            for _ in range(cnt):
                d,p=uleb(b,p); idx+=d; acc,p=uleb(b,p); co,p=uleb(b,p)
                yield idx,co
    def code(s,co):
        b=s.b
        if not co: return b''
        n=u32(b,co+12); return b[co+16:co+16+n*2]
