#!/usr/bin/env python3
"""
Mock stalkerware.
One TCP connection held for the whole session, small upload every 25s.
"""
import socket, time, random, argparse

ap = argparse.ArgumentParser()
ap.add_argument('--host', default='192.168.56.103')
ap.add_argument('--port', type=int, default=8000)
ap.add_argument('--interval', type=float, default=25)
ap.add_argument('--n-events', type=int, default=20)
ap.add_argument('--payload-size', type=int, default=200)
a = ap.parse_args()

print(f'[stalkerware] {a.host}:{a.port} x{a.n_events} on ONE connection')

try:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(10)
    s.connect((a.host, a.port))
    print('  connected')

    for i in range(a.n_events):
        size = a.payload_size + random.randint(-30, 30)
        payload = b'\x00' * size
        try:
            s.sendall(payload)
            print(f'  [{i+1}/{a.n_events}] sent {size} B')
        except Exception as e:
            print(f'  [{i+1}/{a.n_events}] send err: {e}')
            break
        if i < a.n_events - 1:
            time.sleep(a.interval)

    s.shutdown(socket.SHUT_WR)
    try:
        while s.recv(4096):
            pass
    except Exception:
        pass
    s.close()
    print('  closed')
except Exception as e:
    print(f'  fatal: {e}')
