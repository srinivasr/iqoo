#!/usr/bin/env bash
# ============================================================
# DHRASHTA-X — Capture wrapper for 5 mobile threat classes
# 5 classes x 5 runs = 25 pcaps
# ============================================================
IFACE="eth1"
VICTIM="192.168.56.103"
OUT_ROOT="/media/sf_kalishare/DHRASHTA/dataset/pcap"
TOOLS="$HOME/dhrashta-x/tools"

# Pre-flight
if ! sudo -n true 2>/dev/null; then
  echo "[!] sudo not cached. Run 'sudo -v' first."
  exit 1
fi

if ! ip link show "$IFACE" >/dev/null 2>&1; then
  echo "[!] Interface $IFACE not found"
  exit 1
fi

# ---------- one capture ----------
run_one() {
  local class="$1"
  local run_id="$2"
  shift 2
  local script_args=("$@")

  local out_dir="$OUT_ROOT/$class"
  mkdir -p "$out_dir"
  local pcap="$out_dir/${run_id}.pcap"
  [ -f "$pcap" ] && rm -f "$pcap"

  echo ""
  echo "============================================================"
  echo "[+] $class / $run_id"
  echo "============================================================"

  sudo tcpdump -i "$IFACE" -w "$pcap" -U -n -s 0 "host $VICTIM" >/dev/null 2>&1 &
  local tdpid=$!
  sleep 2

  if ! kill -0 "$tdpid" 2>/dev/null; then
    echo "[!] tcpdump failed to start"
    return 1
  fi

  # Run mock attack
  timeout 900 python3 "$TOOLS/$script_args" 2>&1 | sed 's/^/    /'

  sleep 2
  sudo kill -INT "$tdpid" 2>/dev/null || true
  wait "$tdpid" 2>/dev/null || true

  local size
  size=$(stat -c %s "$pcap" 2>/dev/null || echo 0)
  echo "    -> $size bytes"
}

# ---------- main ----------
echo "[*] sudo check passed."
echo "[*] iface=$IFACE victim=$VICTIM"
echo ""

# ---- BANKING_C2 (5 runs, 15 events, varying interval) ----
run_one banking_c2 banking_c2_001 \
  mock_banking_trojan.py --interval 20 --n-events 15 --jitter 0.15
run_one banking_c2 banking_c2_002 \
  mock_banking_trojan.py --interval 30 --n-events 15 --jitter 0.10
run_one banking_c2 banking_c2_003 \
  mock_banking_trojan.py --interval 15 --n-events 20 --jitter 0.20
run_one banking_c2 banking_c2_004 \
  mock_banking_trojan.py --interval 45 --n-events 10 --jitter 0.05
run_one banking_c2 banking_c2_005 \
  mock_banking_trojan.py --interval 25 --n-events 15 --jitter 0.30

# ---- SPYWARE_EXFIL (5 runs, high freq small payload) ----
run_one spyware_exfil spyware_exfil_001 \
  mock_spyware.py --interval 6 --n-events 40 --payload-size 500
run_one spyware_exfil spyware_exfil_002 \
  mock_spyware.py --interval 8 --n-events 40 --payload-size 800
run_one spyware_exfil spyware_exfil_003 \
  mock_spyware.py --interval 4 --n-events 60 --payload-size 300
run_one spyware_exfil spyware_exfil_004 \
  mock_spyware.py --interval 10 --n-events 30 --payload-size 1000
run_one spyware_exfil spyware_exfil_005 \
  mock_spyware.py --interval 6 --n-events 40 --payload-size 600

# ---- STALKERWARE (5 runs, one long connection) ----
run_one stalkerware stalkerware_001 \
  mock_stalkerware.py --interval 20 --n-events 15
run_one stalkerware stalkerware_002 \
  mock_stalkerware.py --interval 15 --n-events 20
run_one stalkerware stalkerware_003 \
  mock_stalkerware.py --interval 30 --n-events 12
run_one stalkerware stalkerware_004 \
  mock_stalkerware.py --interval 10 --n-events 25
run_one stalkerware stalkerware_005 \
  mock_stalkerware.py --interval 25 --n-events 15

# ---- ADWARE_BEACON (5 runs, multi-destination) ----
run_one adware_beacon adware_beacon_001 \
  mock_adware.py --interval 10 --n-events 20 --destinations 103,104,105
run_one adware_beacon adware_beacon_002 \
  mock_adware.py --interval 15 --n-events 20 --destinations 103,104,105,106,107
run_one adware_beacon adware_beacon_003 \
  mock_adware.py --interval 8 --n-events 25 --destinations 103,104
run_one adware_beacon adware_beacon_004 \
  mock_adware.py --interval 12 --n-events 20 --destinations 103,104,105,106
run_one adware_beacon adware_beacon_005 \
  mock_adware.py --interval 6 --n-events 30 --destinations 103,104,105

# ---- BURST_AFTER_RX (5 runs) ----
run_one burst_after_rx burst_after_rx_001 \
  mock_burst_after_rx.py --n-events 6 --payload-size 50000
run_one burst_after_rx burst_after_rx_002 \
  mock_burst_after_rx.py --n-events 6 --payload-size 100000
run_one burst_after_rx burst_after_rx_003 \
  mock_burst_after_rx.py --n-events 8 --payload-size 25000
run_one burst_after_rx burst_after_rx_004 \
  mock_burst_after_rx.py --n-events 5 --payload-size 200000
run_one burst_after_rx burst_after_rx_005 \
  mock_burst_after_rx.py --n-events 10 --payload-size 30000

echo ""
echo "============================================================"
echo "[+] All captures complete"
echo "============================================================"

for cls in banking_c2 spyware_exfil stalkerware adware_beacon burst_after_rx; do
  echo ""
  echo "[$cls]"
  ls -lh "$OUT_ROOT/$cls/" 2>/dev/null
done
