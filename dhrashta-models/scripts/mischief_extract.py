#!/usr/bin/env python3
"""
DHRASHTA-X: Android Mischief PCAP -> 10s window features -> GRU sequences

Usage (Windows / Linux):
    pip install dpkt numpy
    python mischief_extract.py "C:/Users/shivam/Downloads/AndroidMischiefDataset_v2/AndroidMischiefDataset_v2" out/

What it does
  1. Finds every RATxx folder, reads README.md -> victim IP, controller IP, first infection packet
  2. Streams the .pcap (no need to load 150 MB into RAM)
  3. Splits victim traffic into two "pseudo-UID" streams per 10s window:
        - C2 stream    : victim <-> controller   (label 1 after infection)
        - other stream : victim <-> everyone else (label 0, background apps)
     This mimics DHRASHTA-X's per-UID view on the phone.
  4. Computes the SAME features the Kotlin NetworkFeatureExtractor must compute
  5. Builds sequences of SEQ_LEN consecutive windows per stream
  6. Saves windows.csv + sequences.npz (with rat id -> use leave-one-RAT-out!)

IMPORTANT: keep FEATURES below identical to Kotlin. Change both or neither.
"""
import argparse, csv, glob, os, re, socket, sys
from collections import defaultdict

import dpkt
import numpy as np

WINDOW_S = 10          # window length in seconds (match Kotlin)
SEQ_LEN = 10           # windows per GRU sequence
MIN_PKTS = 1           # windows with fewer packets are treated as idle (all-zero)

FEATURES = [
    "bytes_up", "bytes_down", "pkts_up", "pkts_down",
    "up_down_byte_ratio", "mean_pkt_size", "unique_dst_ips", "unique_dst_ports",
    "iat_mean", "iat_std", "tcp_syn_count", "udp_pkt_frac",
]

IPV4 = r"(\d{1,3}(?:\.\d{1,3}){3})"


# ---------------------------------------------------------------- README parsing
def parse_readme(path):
    txt = open(path, encoding="utf-8", errors="ignore").read()

    def ip_after(label):
        m = re.search(label + r"[^\n]*?" + IPV4, txt, re.I)
        if m:
            return m.group(1)
        # Saefko style: "Controller:\n    IPv4: 1.2.3.4"
        m = re.search(label + r"\s*:?\s*\n\s*IPv4\s*:\s*" + IPV4, txt, re.I)
        return m.group(1) if m else None

    m = re.search(r"First Packet of the Infection\s*:\s*(\d+)", txt, re.I)
    return {
        "controller": ip_after(r"Controller(?: IP)?"),
        "victim": ip_after(r"Victim(?: IP)?"),
        "first_pkt": int(m.group(1)) if m else None,
    }


# ---------------------------------------------------------------- pcap reading
def open_pcap(path):
    f = open(path, "rb")
    try:
        r = dpkt.pcap.Reader(f)
    except ValueError:
        f.seek(0)
        r = dpkt.pcapng.Reader(f)
    return r


def iter_ip(reader):
    """Yield (pkt_no starting at 1, ts, ip) for IPv4 packets."""
    dlt = reader.datalink()
    for n, (ts, buf) in enumerate(reader, start=1):
        try:
            if dlt == dpkt.pcap.DLT_EN10MB:
                ip = dpkt.ethernet.Ethernet(buf).data
            elif dlt == dpkt.pcap.DLT_LINUX_SLL:
                ip = dpkt.sll.SLL(buf).data
            elif dlt in (dpkt.pcap.DLT_RAW, 101, 228):
                ip = dpkt.ip.IP(buf)
            else:
                ip = dpkt.ethernet.Ethernet(buf).data
        except Exception:
            continue
        if isinstance(ip, dpkt.ip.IP):
            yield n, ts, ip


# ---------------------------------------------------------------- window features
class Win:
    __slots__ = ("bu", "bd", "pu", "pd", "dst", "ports", "ts", "syn", "udp")

    def __init__(self):
        self.bu = self.bd = self.pu = self.pd = self.syn = self.udp = 0
        self.dst, self.ports, self.ts = set(), set(), []

    def add(self, ts, ip, outgoing):
        size = ip.len
        if outgoing:
            self.bu += size; self.pu += 1
            self.dst.add(ip.dst)
        else:
            self.bd += size; self.pd += 1
        self.ts.append(ts)
        l4 = ip.data
        if isinstance(l4, dpkt.tcp.TCP):
            if outgoing:
                self.ports.add(l4.dport)
            if (l4.flags & dpkt.tcp.TH_SYN) and not (l4.flags & dpkt.tcp.TH_ACK):
                self.syn += 1
        elif isinstance(l4, dpkt.udp.UDP):
            self.udp += 1
            if outgoing:
                self.ports.add(l4.dport)

    def vector(self):
        n = self.pu + self.pd
        if n < MIN_PKTS:
            return [0.0] * len(FEATURES)
        iat = np.diff(sorted(self.ts)) if n > 1 else np.zeros(1)
        return [
            float(self.bu), float(self.bd), float(self.pu), float(self.pd),
            self.bu / (self.bd + 1.0),
            (self.bu + self.bd) / n,
            float(len(self.dst)), float(len(self.ports)),
            float(iat.mean()), float(iat.std()),
            float(self.syn), self.udp / n,
        ]


def process_rat(folder, overrides):
    name = os.path.basename(folder.rstrip("/\\"))
    readme = os.path.join(folder, "README.md")
    pcaps = [p for p in glob.glob(os.path.join(folder, "*.pcap*")) if not p.endswith(".zip")]
    if not pcaps or not os.path.exists(readme):
        print(f"[skip] {name}: README or pcap missing")
        return []

    meta = parse_readme(readme)
    meta.update({k: v for k, v in overrides.get(name, {}).items() if v})
    if not (meta["victim"] and meta["controller"] and meta["first_pkt"]):
        print(f"[skip] {name}: could not parse README -> {meta}. Use --override.")
        return []

    victim = socket.inet_aton(meta["victim"])
    c2 = socket.inet_aton(meta["controller"])
    print(f"[{name}] victim={meta['victim']} c2={meta['controller']} "
          f"infection_pkt={meta['first_pkt']}  ({os.path.basename(pcaps[0])})")

    windows = defaultdict(Win)          # (stream, win_idx) -> Win
    infected_from = None                # window index where infection starts
    t0 = None

    for n, ts, ip in iter_ip(open_pcap(pcaps[0])):
        if ip.src != victim and ip.dst != victim:
            continue
        if t0 is None:
            t0 = ts
        w = int((ts - t0) // WINDOW_S)
        if n >= meta["first_pkt"] and infected_from is None:
            infected_from = w
        outgoing = ip.src == victim
        peer = ip.dst if outgoing else ip.src
        stream = "c2" if peer == c2 else "other"
        windows[(stream, w)].add(ts, ip, outgoing)

    if infected_from is None:
        print(f"  ! infection packet never reached, skipping")
        return []

    rows = []
    last = max(w for _, w in windows)
    for stream in ("c2", "other"):
        for w in range(0, last + 1):
            vec = windows[(stream, w)].vector() if (stream, w) in windows else [0.0] * len(FEATURES)
            label = 1 if (stream == "c2" and w >= infected_from) else 0
            rows.append({"rat": name, "stream": stream, "win": w, "label": label,
                         **dict(zip(FEATURES, vec))})
    n_mal = sum(r["label"] for r in rows)
    print(f"  windows: {len(rows)}  malicious: {n_mal}  benign: {len(rows) - n_mal}")
    return rows


def build_sequences(rows):
    """Consecutive SEQ_LEN windows per (rat, stream). Label = label of LAST window."""
    by = defaultdict(list)
    for r in rows:
        by[(r["rat"], r["stream"])].append(r)
    X, y, rat = [], [], []
    for (rname, _), rs in by.items():
        rs.sort(key=lambda r: r["win"])
        mat = np.array([[r[f] for f in FEATURES] for r in rs], dtype=np.float32)
        labels = [r["label"] for r in rs]
        for end in range(SEQ_LEN, len(rs) + 1):
            seq = mat[end - SEQ_LEN:end]
            if not seq.any():          # skip fully idle sequences
                continue
            X.append(seq); y.append(labels[end - 1]); rat.append(rname)
    return np.stack(X), np.array(y, dtype=np.int8), np.array(rat)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dataset_dir", help="folder containing RAT01_..., RAT02_... folders")
    ap.add_argument("out_dir")
    ap.add_argument("--override", action="append", default=[],
                    help="RATNAME:victim_ip:controller_ip:first_pkt if README parsing fails")
    a = ap.parse_args()

    overrides = {}
    for o in a.override:
        n, v, c, f = o.split(":")
        overrides[n] = {"victim": v, "controller": c, "first_pkt": int(f) if f else None}

    folders = sorted(d for d in glob.glob(os.path.join(a.dataset_dir, "RAT*")) if os.path.isdir(d))
    if not folders:
        sys.exit(f"No RAT* folders found in {a.dataset_dir}")

    rows = []
    for d in folders:
        rows += process_rat(d, overrides)
    if not rows:
        sys.exit("Nothing extracted.")

    os.makedirs(a.out_dir, exist_ok=True)
    csv_path = os.path.join(a.out_dir, "windows.csv")
    with open(csv_path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        w.writeheader(); w.writerows(rows)

    X, y, rat = build_sequences(rows)
    np.savez_compressed(os.path.join(a.out_dir, "sequences.npz"),
                        X=X, y=y, rat=rat, features=np.array(FEATURES))
    print(f"\nSaved {csv_path}")
    print(f"Saved sequences.npz  X={X.shape}  malicious={int(y.sum())}  benign={int((y == 0).sum())}")
    print("Per-RAT sequences:", {r: int((rat == r).sum()) for r in np.unique(rat)})
    print("\nNext: train with leave-one-RAT-out (hold one RAT out completely).")


if __name__ == "__main__":
    main()
