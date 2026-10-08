#!/usr/bin/env python3
"""
DHRASHTA unified feature extractor - v3 (finer multi-scale).
Adds: configurable min window duration, more window sizes.
Same 65-feature vector as v2.
"""
import argparse
import math
import statistics
import subprocess
from collections import defaultdict
from pathlib import Path

import pandas as pd

BURST_GAP = 0.01
SILENCE_GAP = 1.0
MIN_WINDOW_PKTS = 3
MAX_SYN_PER_SEC = 20

TSHARK_FIELDS = [
    "frame.time_epoch", "frame.len",
    "ip.src", "ip.dst", "ip.proto",
    "tcp.srcport", "tcp.dstport",
    "udp.srcport", "udp.dstport",
    "tcp.flags", "tcp.window_size_value",
    "tls.handshake.type",
    "tls.handshake.extensions_server_name",
    "tls.handshake.ja3", "tls.handshake.ja3s",
    "dns.qry.name", "dns.qry.type",
    "dns.flags.response", "dns.flags.rcode",
    "http.request.method", "http.request.uri", "http.user_agent",
    "icmp.type",
]


def mean(v): return statistics.mean(v) if v else 0.0
def std(v): return statistics.stdev(v) if len(v) > 1 else 0.0
def cv(v):
    m = mean(v)
    return (std(v) / m) if m else 0.0
def pct(v, p):
    if not v: return 0.0
    s = sorted(v)
    if len(s) == 1: return float(s[0])
    k = (len(s) - 1) * p
    lo, hi = math.floor(k), math.ceil(k)
    if lo == hi: return float(s[lo])
    return s[lo] * (hi - k) + s[hi] * (k - lo)
def shannon(counts):
    total = sum(counts)
    if total <= 0: return 0.0
    acc = 0.0
    for c in counts:
        if c > 0:
            p = c / total
            acc -= p * math.log2(p)
    return acc
def qname_entropy(name):
    if not name: return 0.0
    name = name.rstrip(".")
    if not name: return 0.0
    counts = defaultdict(int)
    for ch in name: counts[ch] += 1
    total = sum(counts.values())
    e = 0.0
    for c in counts.values():
        p = c / total
        e -= p * math.log2(p)
    return e


def run_tshark(pcap):
    cmd = ["tshark", "-r", str(pcap), "-T", "fields",
           "-E", "separator=|", "-E", "occurrence=f"]
    for f in TSHARK_FIELDS: cmd += ["-e", f]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=1800)
    except subprocess.TimeoutExpired:
        return []
    if r.returncode != 0: return []
    packets = []
    for line in r.stdout.splitlines():
        p = line.split("|")
        while len(p) < len(TSHARK_FIELDS): p.append("")
        try:
            ts = float(p[0]); ln = int(p[1]) if p[1] else 0
        except ValueError:
            continue
        flags = 0
        try: flags = int(p[9], 16) if p[9] else 0
        except ValueError: pass
        tls_hello = 0
        if p[11] and "1" in p[11].split(","): tls_hello = 1
        dns_q = 0
        if p[15] and p[17] != "1": dns_q = 1
        http_req = 1 if p[19] else 0
        packets.append({
            "ts": ts, "len": ln,
            "src": p[2], "dst": p[3], "proto": p[4],
            "sport": p[5] or p[7], "dport": p[6] or p[8],
            "syn": int(bool(flags & 0x02)), "ack": int(bool(flags & 0x10)),
            "rst": int(bool(flags & 0x04)), "fin": int(bool(flags & 0x01)),
            "psh": int(bool(flags & 0x08)),
            "tcp_win": int(p[10]) if p[10] else 0,
            "tls_hello": tls_hello,
            "tls_sni": p[12], "ja3": p[13], "ja3s": p[14],
            "dns_qname": p[15], "dns_qtype": p[16], "dns_rcode": p[18],
            "dns_q": dns_q, "http_req": http_req,
            "http_uri": p[20], "http_ua": p[21], "icmp_type": p[22],
        })
    return packets


def detect_beacon_events(packets, primary_src):
    events = []
    for p in packets:
        if p["src"] != primary_src: continue
        if p["proto"] == "6" and p["syn"] == 1 and p["ack"] == 0:
            events.append({"ts": p["ts"], "type": "tcp_syn", "dst": p["dst"], "dport": p["dport"]})
        elif p["tls_hello"]:
            events.append({"ts": p["ts"], "type": "tls", "dst": p["dst"], "dport": p["dport"]})
        elif p["http_req"]:
            events.append({"ts": p["ts"], "type": "http", "dst": p["dst"], "dport": p["dport"]})
        elif p["dns_q"]:
            events.append({"ts": p["ts"], "type": "dns", "dst": p["dst"], "dport": p["dport"]})
    events.sort(key=lambda e: e["ts"])
    kept = []
    for e in events:
        if kept and (e["ts"] - kept[-1]["ts"]) <= 2.0 \
           and e["dst"] == kept[-1]["dst"] and e["dport"] == kept[-1]["dport"]:
            continue
        kept.append(e)
    buckets = defaultdict(list); others = []
    for e in kept:
        if e["type"] == "tcp_syn": buckets[(int(e["ts"]), e["dst"])].append(e)
        else: others.append(e)
    out = list(others)
    for evs in buckets.values():
        if len(evs) > MAX_SYN_PER_SEC:
            evs.sort(key=lambda x: x["ts"])
            out.append(evs[len(evs) // 2])
        else: out.extend(evs)
    out.sort(key=lambda e: e["ts"])
    return out


def periodicity(times):
    if len(times) < 4: return None, None, None
    t0 = times[0]; span = times[-1] - t0
    n = int(span / 0.5) + 2
    series = [0] * n
    for t in times:
        b = int((t - t0) / 0.5)
        if 0 <= b < n: series[b] += 1
    m = sum(series) / n
    c = [x - m for x in series]
    denom = sum(x * x for x in c)
    if denom <= 0: return None, None, None
    acf = 0.0
    for lag in range(1, min(n // 2, 20) + 1):
        num = sum(c[i] * c[i - lag] for i in range(lag, n))
        v = abs(num / denom)
        if v > acf: acf = v
    powers = []
    for k in range(1, n // 2):
        re = im = 0.0
        for j, xj in enumerate(c):
            ang = -2 * math.pi * k * j / n
            re += xj * math.cos(ang); im += xj * math.sin(ang)
        powers.append((k, re * re + im * im))
    if not powers: return min(acf, 1.0), None, None
    max_p = max(p for _, p in powers)
    k0 = next((k for k, p in powers if p > 0.5 * max_p), None)
    dom = (n * 0.5 / k0) if k0 else None
    mp = sum(p for _, p in powers) / len(powers)
    ratio = min(max_p / (mp * len(powers)), 1.0) if mp > 0 else None
    return min(acf, 1.0), ratio, dom


def window_features(packets, w_start, w_end, primary_src, window_id, min_dur):
    items = [p for p in packets if w_start <= p["ts"] < w_end]
    if len(items) < MIN_WINDOW_PKTS: return None
    ts = sorted(p["ts"] for p in items)
    dur = ts[-1] - ts[0]
    if dur < min_dur: return None

    sizes = [p["len"] for p in items]
    total_b = sum(sizes); n = len(items)
    out = [p for p in items if p["src"] == primary_src]
    inc = [p for p in items if p["dst"] == primary_src]
    out_b = sum(p["len"] for p in out)
    in_b = sum(p["len"] for p in inc)
    t0 = ts[0]
    sec = defaultdict(lambda: {"b": 0, "p": 0})
    for p in items:
        s = int(p["ts"] - t0)
        sec[s]["b"] += p["len"]; sec[s]["p"] += 1
    ps_b = [v["b"] for v in sec.values()]
    ps_p = [v["p"] for v in sec.values()]
    mean_rate = total_b * 8 / dur if dur else 0

    proto_b = defaultdict(int)
    for p in items:
        k = {"6": "tcp", "17": "udp", "1": "icmp"}.get(p["proto"], "other")
        proto_b[k] += p["len"]

    n_small = sum(1 for s in sizes if s < 100)
    n_med = sum(1 for s in sizes if 100 <= s < 500)
    n_large = sum(1 for s in sizes if s >= 500)

    flow_b = defaultdict(int)
    for p in items:
        flow_b[(p["src"], p["dst"], p["sport"], p["dport"])] += p["len"]
    flow_list = sorted(flow_b.values(), reverse=True)
    src_b = defaultdict(int)
    for p in items:
        if p["src"]: src_b[p["src"]] += p["len"]
    src_list = sorted(src_b.values(), reverse=True)
    dst_ips = {p["dst"] for p in items if p["dst"]}
    dst_ports = {p["dport"] for p in items if p["dport"]}
    src_ports = {p["sport"] for p in items if p["sport"]}

    gaps = [ts[i] - ts[i-1] for i in range(1, len(ts))]
    bursts, cur, in_b = 0, 0, False
    burst_sizes = []
    for g in gaps:
        if g <= BURST_GAP:
            if not in_b: in_b, cur = True, 1
            cur += 1
        else:
            if in_b:
                bursts += 1; burst_sizes.append(cur)
                in_b, cur = False, 0
    if in_b:
        bursts += 1; burst_sizes.append(cur)
    silence = sum(1 for g in gaps if g >= SILENCE_GAP)

    evts = detect_beacon_events(items, primary_src)
    evts_t = sorted(e["ts"] for e in evts)
    iats = [evts_t[i] - evts_t[i-1] for i in range(1, len(evts_t))]
    acf, fft_r, dom = periodicity(evts_t)

    tls_hello = sum(1 for p in out if p["tls_hello"])
    tls_snis = {p["tls_sni"] for p in out if p["tls_sni"]}
    ja3s = {p["ja3"] for p in out if p["ja3"]}
    ja3s_srv = {p["ja3s"] for p in out if p["ja3s"]}

    dns_qs = [p for p in out if p["dns_q"]]
    dns_names = [p["dns_qname"] for p in dns_qs if p["dns_qname"]]
    dns_lengths = [len(n) for n in dns_names]
    dns_entropies = [qname_entropy(n) for n in dns_names]
    dns_qtypes = {p["dns_qtype"] for p in out if p["dns_qtype"]}
    dns_rcodes = {p["dns_rcode"] for p in out if p["dns_rcode"]}
    dns_nx = sum(1 for p in out if p["dns_rcode"] == "3")

    http_reqs = sum(1 for p in out if p["http_req"])
    http_uris = [p["http_uri"] for p in out if p["http_uri"]]
    http_uas = {p["http_ua"] for p in out if p["http_ua"]}

    tcp_syn = sum(1 for p in out if p["syn"] and not p["ack"])
    tcp_synack = sum(1 for p in inc if p["syn"] and p["ack"])
    tcp_rst = sum(1 for p in items if p["rst"])
    tcp_fin = sum(1 for p in items if p["fin"])
    tcp_psh = sum(1 for p in items if p["psh"])

    return {
        "window_id": window_id, "duration_seconds": round(dur, 3),
        "packet_count": n, "byte_count": total_b, "flow_count": len(flow_b),
        "outbound_packets": len(out), "inbound_packets": len(inc),
        "outbound_bytes": out_b, "inbound_bytes": in_b,
        "byte_ratio_out_in": (out_b / in_b) if in_b else 0,
        "packet_ratio_out_in": (len(out) / len(inc)) if inc else 0,
        "asymmetry_index": ((out_b - in_b) / (out_b + in_b)) if (out_b + in_b) else 0,
        "pps": n / dur, "bps": mean_rate,
        "bps_peak_1s": max(ps_b) * 8 if ps_b else 0,
        "pps_peak_1s": max(ps_p) if ps_p else 0,
        "peak_to_mean_ratio": (max(ps_b) * 8 / mean_rate) if mean_rate else 0,
        "rate_cv_1s": cv(ps_b),
        "packet_size_mean": mean(sizes), "packet_size_std": std(sizes),
        "packet_size_min": min(sizes), "packet_size_max": max(sizes),
        "packet_size_p50": pct(sizes, 0.5),
        "packet_size_p95": pct(sizes, 0.95),
        "packet_size_p99": pct(sizes, 0.99),
        "frac_small_pkts": n_small / n, "frac_medium_pkts": n_med / n,
        "frac_large_pkts": n_large / n,
        "packet_size_entropy": shannon([sizes.count(s) for s in set(sizes)]),
        "pct_bytes_tcp": proto_b["tcp"] / total_b if total_b else 0,
        "pct_bytes_udp": proto_b["udp"] / total_b if total_b else 0,
        "pct_bytes_icmp": proto_b["icmp"] / total_b if total_b else 0,
        "protocol_count": sum(1 for k in ("tcp", "udp", "icmp") if proto_b[k] > 0),
        "unique_src_ips": len(src_b), "unique_dst_ips": len(dst_ips),
        "unique_dst_ports": len(dst_ports), "unique_src_ports": len(src_ports),
        "top1_flow_byte_share": flow_list[0] / total_b if flow_list and total_b else 0,
        "top1_src_byte_share": src_list[0] / total_b if src_list and total_b else 0,
        "burst_count": bursts, "burst_size_mean": mean(burst_sizes),
        "burst_size_max": max(burst_sizes) if burst_sizes else 0,
        "silence_gaps_1s": silence,
        "packet_iat_mean": mean(gaps), "packet_iat_cv": cv(gaps),
        "beacon_event_count": len(evts),
        "beacon_iat_mean": mean(iats), "beacon_iat_cv": cv(iats),
        "beacon_regularity_score": 1.0 / (1.0 + cv(iats)) if iats else 0.0,
        "periodicity_acf_peak": acf if acf is not None else "",
        "periodicity_fft_peak_ratio": fft_r if fft_r is not None else "",
        "fft_dominant_period_sec": dom if dom is not None else "",
        "beacons_per_minute": len(evts) * 60.0 / dur if dur else 0,
        "tcp_syn_count": tcp_syn, "tcp_synack_count": tcp_synack,
        "tcp_rst_count": tcp_rst, "tcp_fin_count": tcp_fin,
        "tcp_psh_count": tcp_psh,
        "syn_to_synack_ratio": tcp_syn / tcp_synack if tcp_synack else tcp_syn,
        "tls_clienthello_count": tls_hello,
        "tls_sni_cardinality": len(tls_snis),
        "ja3_cardinality": len(ja3s), "ja3s_cardinality": len(ja3s_srv),
        "dns_query_count": len(dns_qs),
        "dns_qname_len_mean": mean(dns_lengths),
        "dns_qname_len_max": max(dns_lengths) if dns_lengths else 0,
        "dns_qname_entropy_mean": mean(dns_entropies),
        "dns_qname_entropy_max": max(dns_entropies) if dns_entropies else 0,
        "dns_qtype_cardinality": len(dns_qtypes),
        "dns_rcode_cardinality": len(dns_rcodes),
        "dns_nxdomain_ratio": dns_nx / len(dns_qs) if dns_qs else 0,
        "dns_unique_qnames": len(set(dns_names)),
        "http_request_count": http_reqs,
        "http_uri_entropy": shannon([http_uris.count(u) for u in set(http_uris)]) if http_uris else 0,
        "http_ua_cardinality": len(http_uas),
    }


def extract_pcap_multiscale(pcap, window_sizes, stride_frac, min_dur):
    packets = run_tshark(pcap)
    if not packets: return []
    src_counts = defaultdict(int)
    for p in packets:
        if p["src"]: src_counts[p["src"]] += 1
    if not src_counts: return []
    primary_src = max(src_counts, key=src_counts.get)
    t0 = min(p["ts"] for p in packets)
    t1 = max(p["ts"] for p in packets)
    rows = []
    for wsize in window_sizes:
        stride = wsize * stride_frac
        if stride <= 0: stride = wsize
        start = t0; wid = 0
        while start + wsize <= t1 + 0.001:
            f = window_features(packets, start, start + wsize,
                                primary_src, wid, min_dur)
            if f:
                f["run_id"] = pcap.stem
                f["window_id"] = wid
                f["window_size_sec"] = wsize
                f["window_start_sec"] = round(start - t0, 3)
                f["window_end_sec"] = round(start + wsize - t0, 3)
                f["primary_source_ip"] = primary_src
                rows.append(f)
            start += stride; wid += 1
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pcap-dir", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--windows", default="2,3,5,10,15,30,60,120")
    ap.add_argument("--stride", type=float, default=0.25)
    ap.add_argument("--min-window-dur", type=float, default=1.5)
    ap.add_argument("--pattern", default="*.pcap")
    args = ap.parse_args()

    window_sizes = sorted(float(w) for w in args.windows.split(",") if w.strip())
    print("[config] window sizes: {0}".format(window_sizes))
    print("[config] stride fraction: {0}".format(args.stride))
    print("[config] min window duration: {0}".format(args.min_window_dur))

    pcap_dir = Path(args.pcap_dir)
    pcaps = sorted(pcap_dir.rglob(args.pattern))
    if not pcaps:
        raise SystemExit("No pcaps found in {0}".format(pcap_dir))

    all_rows = []
    for pcap in pcaps:
        rows = extract_pcap_multiscale(pcap, window_sizes, args.stride,
                                        args.min_window_dur)
        print("[+] {0:45s} -> {1:5d} windows".format(pcap.stem, len(rows)))
        all_rows.extend(rows)

    if not all_rows:
        raise SystemExit("No windows produced.")

    df = pd.DataFrame(all_rows)
    front = ["run_id", "window_id", "window_size_sec",
             "window_start_sec", "window_end_sec", "primary_source_ip"]
    cols = front + [c for c in df.columns if c not in front]
    df = df[cols]

    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    df.to_csv(args.output, index=False)

    print("")
    print("[+] Wrote {0}".format(args.output))
    print("    total rows: {0}".format(len(df)))
    print("    columns:    {0}".format(df.shape[1]))
    print("")
    print("    rows per window size:")
    print(df.groupby("window_size_sec").size().to_string())


if __name__ == "__main__":
    main()
