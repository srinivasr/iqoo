import fcntl, struct, os, socket, select, sys
from pyroute2 import IPRoute
fd = os.open('/dev/net/tun', os.O_RDWR)
fcntl.ioctl(fd, 0x400454ca, struct.pack('16sH', b'tun7', 0x0001 | 0x1000))
ip = IPRoute(); idx = ip.link_lookup(ifname='tun7')[0]
ip.addr('add', index=idx, address='10.0.0.2', prefixlen=32)
ip.link('set', index=idx, state='up', mtu=1500)
ip.route('add', dst='198.18.0.0/24', oif=idx)
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.bind(('127.0.0.1', 47002))
to = ('127.0.0.1', 47001)
print('bridge up', flush=True)
while True:
    r, _, _ = select.select([fd, s], [], [])
    if fd in r:
        pkt = os.read(fd, 65535)
        if pkt and (pkt[0] >> 4) == 4: s.sendto(pkt, to)
    if s in r:
        pkt, _ = s.recvfrom(65535); os.write(fd, pkt)
