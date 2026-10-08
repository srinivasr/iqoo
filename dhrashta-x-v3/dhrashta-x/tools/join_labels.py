#!/usr/bin/env python3
"""
DHRASHTA-X label joiner.
Reads *_unified.csv from DHRASHTA-X features dir.
Writes training_dataset.csv to DHRASHTA-X data dir.
DHRASHTA project is NOT touched.
"""
import sys
import pandas as pd
from pathlib import Path

# DHRASHTA-X specific paths
FEAT_DIR = Path.home() / "dhrashta-x/data/features"
OUT = Path.home() / "dhrashta-x/data/training_dataset.csv"

# DHRASHTA-X class map. Order matters (specific first).
LABELS = {
    # BENIGN
    "benign_tls":     ("BENIGN", "normal_background"),
    "benign_dns":     ("BENIGN", "normal_background"),
    "benign_ntp":     ("BENIGN", "normal_background"),
    "benign_syslog":  ("BENIGN", "normal_background"),
    "benign_http":    ("BENIGN", "normal_background"),
    "benign_ssh":     ("BENIGN", "normal_background"),
    "benign":         ("BENIGN", "normal_background"),

    # C2 over raw TCP
    "c2_beacon":      ("C2_BEACON", "botnet_c2_beaconing"),
    "c2_fixed":       ("C2_BEACON", "botnet_c2_beaconing"),
    "c2_jitter":      ("C2_BEACON", "botnet_c2_beaconing"),
    "c2_skew":        ("C2_BEACON", "botnet_c2_beaconing"),
    "c2_persistent":  ("C2_BEACON", "botnet_c2_beaconing"),

    # C2 over TLS
    "c2_tls":         ("TLS_C2", "malware_encrypted"),
    "tls_c2":         ("TLS_C2", "malware_encrypted"),

    # DNS C2 (two subtypes kept separate)
    "dns_tunnel":     ("DNS_DNSCAT2", "dga_dns_tunnelling"),
    "c2_dns":         ("DNS_DNSCAT2", "dga_dns_tunnelling"),
    "dns_c2":         ("DNS_DNSCAT2", "dga_dns_tunnelling"),
    "dga":            ("DNS_DGA", "dga_dns_tunnelling"),

    # Volumetric DDoS
    "syn_flood":      ("SYN_FLOOD", "volumetric_ddos"),
    "udp_flood":      ("UDP_FLOOD", "volumetric_ddos"),
    "udp_reflect":    ("UDP_REFLECT", "volumetric_ddos"),
    "udp_amplification": ("UDP_REFLECT", "volumetric_ddos"),

    # Recon
    "port_scan":      ("PORT_SCAN", "reconnaissance"),
    "nmap":           ("PORT_SCAN", "reconnaissance"),

    # Exfil (burst_after_rx folded into EXFIL)
    "exfil":          ("EXFIL", "data_exfiltration"),
    "burst_after_rx": ("EXFIL", "data_exfiltration"),

    # DHRASHTA-X specific mobile threat classes
    "banking_c2":     ("BANKING_C2", "banking_trojan"),
    "banking_trojan": ("BANKING_C2", "banking_trojan"),
    "spyware_exfil":  ("SPYWARE_EXFIL", "spyware"),
    "spyware":        ("SPYWARE_EXFIL", "spyware"),
    "stalkerware":    ("STALKERWARE", "stalkerware"),
    "adware_beacon":  ("ADWARE_BEACON", "adware"),
    "adware":         ("ADWARE_BEACON", "adware"),
}

def label_for(run_id):
    rid = str(run_id)
    for prefix, (lab, thr) in LABELS.items():
        if rid.startswith(prefix):
            return lab, thr
    return None, None

def main():
    if not FEAT_DIR.exists():
        sys.exit("DHRASHTA-X features dir not found: {0}".format(FEAT_DIR))

    csvs = sorted(FEAT_DIR.glob("*_unified.csv"))
    if not csvs:
        sys.exit("No *_unified.csv files in {0}".format(FEAT_DIR))

    print("DHRASHTA-X joiner")
    print("Source: {0}".format(FEAT_DIR))
    print("Output: {0}".format(OUT))
    print()
    print("Found {0} unified CSV(s):".format(len(csvs)))
    for c in csvs:
        print("  {0}".format(c.name))
    print()

    frames = []
    dropped_total = 0
    for csv in csvs:
        df = pd.read_csv(csv)
        n_in = len(df)
        labels = [label_for(rid) for rid in df["run_id"]]
        df["label"] = [l for l, _ in labels]
        df["threat_class"] = [t for _, t in labels]

        n_drop = int(df["label"].isna().sum())
        if n_drop:
            bad = df.loc[df["label"].isna(), "run_id"].unique().tolist()
            print("  [!] {0}: dropping {1} rows from unmatched runs:".format(csv.name, n_drop))
            for r in sorted(bad)[:10]:
                print("      {0}".format(r))
            df = df[df["label"].notna()].copy()
        dropped_total += n_drop

        frames.append(df)
        print("  [+] {0:35s} {1:>6d} rows -> {2:>6d} labeled".format(
            csv.name, n_in, len(df)))

    if not frames:
        sys.exit("No labeled rows produced.")

    full = pd.concat(frames, ignore_index=True)
    meta = ["run_id", "window_id", "label", "threat_class"]
    cols = meta + [c for c in full.columns if c not in meta]
    full = full[cols]

    OUT.parent.mkdir(parents=True, exist_ok=True)
    full.to_csv(OUT, index=False, chunksize=1000)

    print()
    print("=" * 62)
    print("[+] Wrote {0}".format(OUT))
    print("    rows:    {0}".format(len(full)))
    print("    columns: {0}".format(full.shape[1]))
    print("    dropped: {0}".format(dropped_total))
    print()
    print("Class counts:")
    print(full.groupby("label").size().to_string())
    print()
    print("Runs per class:")
    print(full.groupby("label")["run_id"].nunique().to_string())
    print("=" * 62)

if __name__ == "__main__":
    main()
