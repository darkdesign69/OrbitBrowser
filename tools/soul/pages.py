import pickle,sys,re,collections
sys.path.insert(0,'/home/claude/tools'); import axml
S='/tmp/claude-0/-home-claude/27e1f639-1fbf-5db2-9645-23b70565c7fd/scratchpad/'
axml.load_arsc(S+'base/resources.arsc')
txt={};
for rid,v in axml.RES.values.items():
    t,n=axml.RES.names[rid]
    if t=='string':
        x=v.get('default') or list(v.values())[0]
        if x[0]==3: txt[n]=axml.RES.strpool[x[1]].replace('\n',' ')
c=pickle.load(open(S+'cls_seq.pkl','rb'))
LIB=re.compile(r'^(abc_|mtrl_|m3_|material_|cast_|mr_|common_|exo_|design_|appbar|bottomsheet|character_|clear_text|error_|fab_|hide_bottom|icon_content|item_view|password_toggle|path_password|searchbar|searchview|side_sheet|call_notification|copy_toast|summary_|v7_|status_bar|androidx)')
def render(seq):
    o=[]
    for x in seq:
        if x[0]=='R':
            if x[1]=='string' and x[2] in txt and not LIB.match(x[2]): o.append(f'"{txt[x[2]]}"')
            elif x[1] in('drawable',) and not LIB.match(x[2]) and not x[2].startswith(('abc_','mtrl_','design_','m3_','ic_mtrl','mr_','cast_','notification','common_')): o.append('[icon:'+x[2]+']')
            elif x[1]=='array': o.append('[array:'+x[2]+']')
        elif x[0]=='P': o.append(f'<{x[1]}.{x[2]}>')
        elif x[0]=='S' and re.match(r'^m[A-Z]\w+$',x[1]): o.append('KEY:'+x[1])
    return o
if __name__=='__main__':
    only=sys.argv[1:] 
    for k in sorted(c):
        nm=k.split('/')[-1].rstrip(';')
        if only and not any(o==nm for o in only): continue
        if '/setting/' not in k and not only: continue
        for cls,m,seq in c[k]:
            r=render(seq)
            if sum(1 for z in r if z.startswith('"'))>=2:
                print('##',cls.split('/')[-1],m); print('  '+' | '.join(r))
