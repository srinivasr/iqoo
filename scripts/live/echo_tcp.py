import socketserver
class H(socketserver.BaseRequestHandler):
    def handle(self):
        while True:
            d=self.request.recv(4096)
            if not d: break
            self.request.sendall(d.upper())
class T(socketserver.ThreadingMixIn, socketserver.TCPServer): daemon_threads=True; allow_reuse_address=True
T(('127.0.0.1',9054),H).serve_forever()
