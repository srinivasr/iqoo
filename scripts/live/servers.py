import http.server, socketserver, threading, socket, os, hashlib
os.makedirs('/tmp/www', exist_ok=True)
data = os.urandom(5*1024*1024); open('/tmp/www/big.bin','wb').write(data)
print('sha', hashlib.sha256(data).hexdigest(), flush=True)
class H(http.server.SimpleHTTPRequestHandler):
    def __init__(s,*a,**k): super().__init__(*a, directory='/tmp/www', **k)
    def log_message(s,*a): pass
    def do_POST(s):
        n=int(s.headers['Content-Length']); body=s.rfile.read(n)
        r=hashlib.sha256(body).hexdigest().encode()
        s.send_response(200); s.send_header('Content-Length',str(len(r))); s.end_headers(); s.wfile.write(r)
class T(socketserver.ThreadingMixIn, http.server.HTTPServer): daemon_threads=True
threading.Thread(target=T(('127.0.0.1',8099),H).serve_forever,daemon=True).start()
u=socket.socket(socket.AF_INET,socket.SOCK_DGRAM); u.bind(('127.0.0.1',9053))
while True:
    d,a=u.recvfrom(65535); u.sendto(d[::-1],a)
