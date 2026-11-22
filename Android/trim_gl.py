import io
p='Gl.java'
raw=open(p,'rb').read()
print('bom',raw[:3])
s=raw.decode('utf-8')
assert '\ufffd' not in s
lines=s.splitlines(keepends=True)
sig='    private void blurPass(int src, int dstFbo, float sx, float sy) {\n'
idx=[i for i,l in enumerate(lines) if l==sig]
print('sig lines',idx)
assert len(idx)==2
a,b=idx[0],idx[1]
# 校验这段里确实是磨砂代码
seg=''.join(lines[a:b])
for kw in ['ensureFrost','frostRegion','drawFrost','blurPassAt','FROST_CAP']:
    assert kw in seg, kw
new=lines[:a]+lines[b:]
open(p,'wb').write(''.join(new).encode('utf-8'))
print('deleted',b-a,'lines; now',len(new))
