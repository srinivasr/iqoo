# mischief_extract.py windowing (10 s windows, same 12 features, same README parsing), with a tiny
# pcap reader instead of dpkt. Writes windows.csv.
import re, os, glob, struct, sys, csv
from collections import defaultdict
import numpy as np
WINDOW_S=10
FEATURES=["bytes_up","bytes_down","pkts_up","pkts_down","up_down_byte_ratio","mean_pkt_size","unique_dst_ips","unique_dst_ports","iat_mean","iat_std","tcp_syn_count","udp_pkt_frac"]
IPV4=r"(\d{1,3}(?:\.\d{1,3}){3})"
def parse_readme(path):
    txt=open(path,encoding="utf-8",errors="ignore").read()
    def ip_after(label):
        m=re.search(label+r"[^\n]*?"+IPV4,txt,re.I)
        if m: return m.group(1)
        m=re.search(label+r"\s*:?\s*\n\s*IPv4\s*:\s*"+IPV4,txt,re.I)
        return m.group(1) if m else None
    m=re.search(r"First Packet of the Infection\s*:\s*(\d+)",txt,re.I)
    return {"controller":ip_after(r"Controller(?: IP)?"),"victim":ip_after(r"Victim(?: IP)?"),"first_pkt":int(m.group(1)) if m else None}
def iter_ip(path):
    f=open(path,'rb'); gh=f.read(24); le=gh[:4]==b'\xd4\xc3\xb2\xa1'; e='<' if le else '>'
    link=struct.unpack(e+'I',gh[20:24])[0]; n=0
    while True:
        h=f.read(16)
        if len(h)<16: break
        sec,usec,incl,orig=struct.unpack(e+'IIII',h); buf=f.read(incl); n+=1
        off=0
        if link==1:
            et=struct.unpack('>H',buf[12:14])[0]; off=14
            while et==0x8100 and len(buf)>=off+4: et=struct.unpack('>H',buf[off+2:off+4])[0]; off+=4
            if et!=0x0800: continue
        elif link==113:
            if struct.unpack('>H',buf[14:16])[0]!=0x0800: continue
            off=16
        ip=buf[off:]
        if len(ip)<20 or ip[0]>>4!=4: continue
        yield n, sec+usec/1e6, ip
class Win:
    __slots__=("bu","bd","pu","pd","dst","ports","ts","syn","udp")
    def __init__(s): s.bu=s.bd=s.pu=s.pd=s.syn=s.udp=0; s.dst,s.ports,s.ts=set(),set(),[]
    def add(s,ts,ip,out):
        size=struct.unpack('>H',ip[2:4])[0]; ihl=(ip[0]&15)*4; proto=ip[9]; dst=ip[16:20]
        if out: s.bu+=size; s.pu+=1; s.dst.add(dst)
        else: s.bd+=size; s.pd+=1
        s.ts.append(ts)
        frag=(struct.unpack('>H',ip[6:8])[0]&0x1FFF)!=0
        if proto==6 and not frag and len(ip)>=ihl+14:
            if out: s.ports.add(struct.unpack('>H',ip[ihl+2:ihl+4])[0])
            fl=ip[ihl+13]
            if (fl&2) and not (fl&16): s.syn+=1
        elif proto==17 and not frag and len(ip)>=ihl+4:
            s.udp+=1
            if out: s.ports.add(struct.unpack('>H',ip[ihl+2:ihl+4])[0])
    def vector(s):
        n=s.pu+s.pd
        if n<1: return [0.0]*12
        iat=np.diff(sorted(s.ts)) if n>1 else np.zeros(1)
        return [float(s.bu),float(s.bd),float(s.pu),float(s.pd),s.bu/(s.bd+1.0),(s.bu+s.bd)/n,float(len(s.dst)),float(len(s.ports)),float(iat.mean()),float(iat.std()),float(s.syn),s.udp/n]
def process(folder,w):
    name=os.path.basename(folder); meta=parse_readme(os.path.join(folder,'README.md'))
    pcap=glob.glob(os.path.join(folder,'*.pcap'))[0]
    if not all(meta.values()): print('skip',name,meta,flush=True); return
    victim=bytes(map(int,meta['victim'].split('.'))); c2=bytes(map(int,meta['controller'].split('.')))
    wins=defaultdict(Win); inf=None; t0=None
    for n,ts,ip in iter_ip(pcap):
        src,dst=ip[12:16],ip[16:20]
        if src!=victim and dst!=victim: continue
        if t0 is None: t0=ts
        wi=int((ts-t0)//WINDOW_S)
        if n>=meta['first_pkt'] and inf is None: inf=wi
        out=src==victim; peer=dst if out else src
        wins[('c2' if peer==c2 else 'other',wi)].add(ts,ip,out)
    last=max(x for _,x in wins)
    for stream in ('c2','other'):
        for wi in range(last+1):
            v=wins[(stream,wi)].vector() if (stream,wi) in wins else [0.0]*12
            w.writerow([name,stream,wi,1 if (stream=='c2' and wi>=inf) else 0]+v)
    print(name,'done windows',last+1,'infection_win',inf,flush=True)
root=sys.argv[1]
with open('windows.csv','w',newline='') as f:
    w=csv.writer(f); w.writerow(['rat','stream','win','label']+FEATURES)
    for d in sorted(glob.glob(os.path.join(root,'RAT*'))):
        if os.path.isdir(d): process(d,w)
print('ALL DONE',flush=True)
