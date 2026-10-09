import random, json, numpy as np
random.seed(7)
FEATURES = ["bytes_up","bytes_down","pkts_up","pkts_down","up_down_byte_ratio","mean_pkt_size","unique_dst_ips","unique_dst_ports","iat_mean","iat_std","tcp_syn_count","udp_pkt_frac"]
class Win:  # mischief_extract.Win with dpkt fields replaced by plain values
    def __init__(s): s.bu=s.bd=s.pu=s.pd=s.syn=s.udp=0; s.dst,s.ports,s.ts=set(),set(),[]
    def add(s,ts,size,out,rip,rport,proto,syn):
        if out: s.bu+=size; s.pu+=1; s.dst.add(rip)
        else: s.bd+=size; s.pd+=1
        s.ts.append(ts)
        if proto==6:
            if out: s.ports.add(rport)
            if syn: s.syn+=1
        elif proto==17:
            s.udp+=1
            if out: s.ports.add(rport)
    def vector(s):
        n=s.pu+s.pd
        if n<1: return [0.0]*12
        iat=np.diff(sorted(s.ts)) if n>1 else np.zeros(1)
        return [float(s.bu),float(s.bd),float(s.pu),float(s.pd),s.bu/(s.bd+1.0),(s.bu+s.bd)/n,float(len(s.dst)),float(len(s.ports)),float(iat.mean()),float(iat.std()),float(s.syn),s.udp/n]
t0=1_791_500_000_000; pkts=[]; t=t0
for i in range(3000):
    t+=random.choice([1,5,20,300,2500,9000]); out=random.random()<0.5
    proto=random.choice([6,6,6,17]); pkts.append((t,random.randint(40,1500),out,random.choice([0x0A000001,0x08080808,0x01010101,0x5DB8D822]),random.choice([443,80,53,5555]),proto,proto==6 and random.random()<0.05))
wins={}
for (ts,size,out,rip,rport,proto,syn) in pkts:
    wins.setdefault(ts//10000,Win()).add(ts/1000.0,size,out,rip,rport,proto,syn)
first=pkts[0][0]//10000; last=pkts[-1][0]//10000
ref=[wins[w].vector() if w in wins else [0.0]*12 for w in range(first,last+1)]
json.dump({'pkts':pkts,'windows':ref},open('feat_case.json','w'))
print('windows',len(ref))
