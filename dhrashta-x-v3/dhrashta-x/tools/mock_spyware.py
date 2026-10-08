#!/usr/bin/env python3
"""
Mock spyware.
Small upload (500 B) every 8s, high frequency.
"""
import socket, time, random, argparse

ap = argparse.ArgumentParser()
ap.add_argument('--host', default='192.168.56.103')
ap.add_argument('--port', type=int, default=8000)
ap.add_argument('--interval', type=float, default=8)
ap.add_argument('--jitter', type=float, default=0.10)
ap.add_argument('--n-events', type=int, default=60)
ap.add_argument('--payload-size', type=int, default=500)
a = ap.parse_args()

print(f'[spyware] {a.host}:{a.port} x{a.n_events} interval={a.interval}s')

for i in range(a.n_events):
    size = a.payload_size + random.randint(-50, 50)
    payload = b'\x00' * size
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(3)
        s.connect((a.host, a.port))
        s.sendall(payload)
        s.shutdown(socket.SHUT_WR)
        s.close()
        print(f'  [{i+1}/{a.n_events}] sent {size} B')
    except Exception as e:
        print(f'  [{i+1}/{a.n_events}] err: {e}')

    if i < a.n_events - 1:
        base = a.interval
        j = base * a.jitter
        sleep_for = max(0.5, base + random.uniform(-j, j))
        time.sleep(sleep_for)
