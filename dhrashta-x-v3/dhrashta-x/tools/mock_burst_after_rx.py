#!/usr/bin/env python3
"""
Mock burst-after-rx.
Waits for inbound trigger, sends large burst in response.
"""
import socket, time, random, argparse

ap = argparse.ArgumentParser()
ap.add_argument('--host', default='192.168.56.103')
ap.add_argument('--port', type=int, default=8000)
ap.add_argument('--n-events', type=int, default=10)
ap.add_argument('--payload-size', type=int, default=50000)
a = ap.parse_args()

print(f'[burst_after_rx] {a.host}:{a.port} x{a.n_events} payload={a.payload_size}')

for i in range(a.n_events):
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(10)
        s.connect((a.host, a.port))
        # Wait for inbound trigger
        try:
            trigger = s.recv(64)
            print(f'  [{i+1}/{a.n_events}] trigger: {len(trigger)} B')
        except Exception:
            print(f'  [{i+1}/{a.n_events}] no trigger, sending anyway')
        # Send burst
        payload = b'\x00' * a.payload_size
        s.sendall(payload)
        s.shutdown(socket.SHUT_WR)
        try:
            while s.recv(4096):
                pass
        except Exception:
            pass
        s.close()
        print(f'  [{i+1}/{a.n_events}] sent {a.payload_size} B')
    except Exception as e:
        print(f'  [{i+1}/{a.n_events}] err: {e}')

    if i < a.n_events - 1:
        time.sleep(5)
