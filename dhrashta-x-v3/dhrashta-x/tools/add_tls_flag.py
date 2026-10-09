#!/usr/bin/env python3
"""
Post-process extracted CSVs to add run_has_tls.
Reads raw TCP payload bytes from each pcap and looks for the TLS
ClientHello signature: bytes 16 03 01/03, then handshake type 01.
Bypasses tshark's port-based dissector.
"""
import subprocess
import sys
from pathlib import Path
import pandas as pd

def pcap_has_tls(pcap):
    """Return 1 if any TCP payload in the pcap starts with a TLS ClientHello."""
    try:
        r = subprocess.run(
            ["tshark", "-r", str(pcap),
             "-Y", "tcp",
             "-T", "fields", "-e", "tcp.payload"],
            capture_output=True, text=True, timeout=120
        )
        for line in r.stdout.splitlines():
            raw = line.strip().replace(":", "").replace(" ", "")
            if len(raw) < 12:
                continue
            # TLS record header: 16 03 XX XX XX
            # Handshake type at byte 5: 01 = ClientHello
            if raw[0:2] == "16" and raw[2:4] == "03":
                if raw[10:12] == "01":
                    return 1
        return 0
    except Exception as e:
        print(f"  [!] {pcap.name}: {e}")
        return 0

def main():
    pcap_root = Path("/media/sf_kalishare/DHRASHTA/dataset/pcap")
    feat_dir = Path.home() / "dhrashta-x/data/features"

    classes = ["tls_c2", "c2_beacon", "banking_c2", "spyware_exfil",
               "stalkerware", "adware_beacon", "benign", "exfil",
               "dns_tunnel", "dga", "port_scan", "syn_flood", "udp_reflect"]

    for cls in classes:
        csv = feat_dir / f"{cls}_unified.csv"
        pcap_dir = pcap_root / cls
        if not csv.exists():
            print(f"[!] Missing CSV: {csv}")
            continue
        if not pcap_dir.exists():
            print(f"[!] Missing pcap dir: {pcap_dir}")
            continue

        df = pd.read_csv(csv)

        run_flags = {}
        for pcap in sorted(pcap_dir.glob("*.pcap")):
            run_flags[pcap.stem] = pcap_has_tls(pcap)

        df["run_has_tls"] = df["run_id"].map(run_flags).fillna(0).astype(int)

        df.to_csv(csv, index=False)
        n_tls = int((df["run_has_tls"] == 1).sum())
        n_total = len(df)
        pct = n_tls / n_total * 100 if n_total else 0
        print(f"{cls:20s} run_has_tls: {n_tls:5d}/{n_total:5d} rows = {pct:.1f}%")
        for rid, flag in sorted(run_flags.items()):
            print(f"    {rid:35s} tls={flag}")

if __name__ == "__main__":
    main()
