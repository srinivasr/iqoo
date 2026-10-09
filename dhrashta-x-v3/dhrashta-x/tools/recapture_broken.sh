#!/usr/bin/env bash
IFACE="eth1"
VICTIM="192.168.56.103"
OUT_ROOT="/media/sf_kalishare/DHRASHTA/dataset/pcap"
TOOLS="$HOME/dhrashta-x/tools"

run_one() {
  local class="$1"; local run_id="$2"; shift 2
  local out_dir="$OUT_ROOT/$class"
  mkdir -p "$out_dir"
  local pcap="$out_dir/${run_id}.pcap"
  [ -f "$pcap" ] && rm -f "$pcap"
  echo ""; echo "=== $class / $run_id ==="
  sudo tcpdump -i "$IFACE" -w "$pcap" -U -n -s 0 "host $VICTIM" >/dev/null 2>&1 &
  local tdpid=$!
  sleep 2
  timeout 900 python3 "$TOOLS/$@" 2>&1 | sed 's/^/    /'
  sleep 2
  sudo kill -INT "$tdpid" 2>/dev/null || true
  wait "$tdpid" 2>/dev/null || true
  local size; size=$(stat -c %s "$pcap" 2>/dev/null || echo 0)
  echo "    -> $size bytes"
}

# Stalkerware
run_one stalkerware stalkerware_001 mock_stalkerware.py --interval 20 --n-events 20 --payload-size 200
run_one stalkerware stalkerware_002 mock_stalkerware.py --interval 15 --n-events 25 --payload-size 180
run_one stalkerware stalkerware_003 mock_stalkerware.py --interval 30 --n-events 15 --payload-size 220
run_one stalkerware stalkerware_004 mock_stalkerware.py --interval 10 --n-events 30 --payload-size 190
run_one stalkerware stalkerware_005 mock_stalkerware.py --interval 25 --n-events 20 --payload-size 210

# Adware
run_one adware_beacon adware_beacon_001 mock_adware.py --interval 10 --n-events 30 --payload-size 100
run_one adware_beacon adware_beacon_002 mock_adware.py --interval 15 --n-events 25 --payload-size 120
run_one adware_beacon adware_beacon_003 mock_adware.py --interval 8 --n-events 35 --payload-size 90
run_one adware_beacon adware_beacon_004 mock_adware.py --interval 12 --n-events 25 --payload-size 110
run_one adware_beacon adware_beacon_005 mock_adware.py --interval 6 --n-events 40 --payload-size 100

# Burst after rx
run_one burst_after_rx burst_after_rx_001 mock_burst_after_rx.py --n-events 6 --payload-size 50000
run_one burst_after_rx burst_after_rx_002 mock_burst_after_rx.py --n-events 6 --payload-size 100000
run_one burst_after_rx burst_after_rx_003 mock_burst_after_rx.py --n-events 8 --payload-size 25000
run_one burst_after_rx burst_after_rx_004 mock_burst_after_rx.py --n-events 5 --payload-size 200000
run_one burst_after_rx burst_after_rx_005 mock_burst_after_rx.py --n-events 10 --payload-size 30000

echo ""
echo "=== Done ==="
for cls in stalkerware adware_beacon burst_after_rx; do
  echo "[$cls]"
  ls -lh "$OUT_ROOT/$cls/" 2>/dev/null
done
