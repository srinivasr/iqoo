#!/usr/bin/env python3
"""
Mock adware.
Tiny beacons to multiple destination ports, high cardinality.
"""
import socket, time, random, argparse

ap = argparse.ArgumentParser()
ap.add_argument('--host', default='192.168.56.103')
ap.add_argument('--ports', default='8000,8001,8002,8003,8004')
ap.add_argument('--interval', type=float, default=15)
ap.add_argument('--jitter', type=float, default=0.15)
ap.add_argument('--n-events', type=int, default=30)
ap.add_argument('--payload-size', type=int, default=100)
a = ap.parse_args()

ports = [int(p.strip()) for p in a.ports.split(',')]
print(f'[adware] {len(ports)} ports on {a.host}, x{a.n_events} interval={a.interval}s')

for i in range(a.n_events):
    port = random.choice(ports)
    size = a.payload_size + random.randint(-20, 20)
    payload = b'\x00' * size
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(2)
        s.connect((a.host, port))
        s.sendall(payload)
        s.close()
        print(f'  [{i+1}/{a.n_events}] -> :{port} {size} B')
    except Exception as e:
        print(f'  [{i+1}/{a.n_events}] -> :{port} err: {e}')

    if i < a.n_events - 1:
        base = a.interval
        j = base * a.jitter
        sleep_for = max(1.0, base + random.uniform(-j, j))
        time.sleep(sleep_for)
