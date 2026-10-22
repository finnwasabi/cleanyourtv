import sys
lines=open(sys.argv[1]).read().split('\n'); hdr=None; rows=[]
for l in lines:
    if l.startswith('Flags,'): hdr=l.strip().strip(',').split(','); continue
    if hdr and l and l[0].isdigit() and l.count(',')>10:
        v=l.strip().strip(',').split(',')
        if len(v)>=len(hdr): rows.append(dict(zip(hdr,map(int,v[:len(hdr)]))))
def ms(a,b,r): return (r[b]-r[a])/1e6
import statistics as st
t=[ms('IntendedVsync','FrameCompleted',r) for r in rows if r['Flags']==0]
iss=[ms('IssueDrawCommandsStart','SwapBuffers',r) for r in rows if r['Flags']==0]
gpu=[ms('SwapBuffers','FrameCompleted',r) for r in rows if r['Flags']==0]
dq=[r['DequeueBufferDuration']/1e6 for r in rows if r['Flags']==0]
print(f"{len(t)} frames  total med {st.median(t):.1f}  issue med {st.median(iss):.1f}  swap->done med {st.median(gpu):.1f}  dequeue med {st.median(dq):.1f}")
