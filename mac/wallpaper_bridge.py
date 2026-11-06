#!/usr/bin/env python3
"""Relay temporary wallpaper uploads and search input to the local emulator."""
import argparse
import http.server
import ipaddress
import re
import selectors
import signal
import socket
import socketserver
import subprocess
import threading
import time

LAN_PORT = 18766
DISCOVERY_PORT = 18767


def lan_address():
    route = subprocess.run(['/sbin/route', '-n', 'get', 'default'], capture_output=True, text=True).stdout
    primary = re.search(r'interface:\s+(en\d+)', route)
    interfaces = [primary.group(1)] if primary else []
    interfaces += subprocess.check_output(['/sbin/ifconfig', '-l'], text=True).split()
    for interface in dict.fromkeys(interfaces):
        if not re.fullmatch(r'en\d+', interface):
            continue
        address = subprocess.run(['/usr/sbin/ipconfig', 'getifaddr', interface], capture_output=True, text=True).stdout.strip()
        try:
            ip = ipaddress.IPv4Address(address)
            if ip.is_private and not ip.is_link_local:
                return address
        except ValueError:
            continue
    raise SystemExit('No local IPv4 network for phone uploads')


class Relay(socketserver.BaseRequestHandler):
    def handle(self):
        if not self.server.slots.acquire(blocking=False):
            return
        try:
            self.request.settimeout(5)
            prefix = b''
            while b'\n' not in prefix and len(prefix) < 4096:
                chunk = self.request.recv(4096 - len(prefix))
                if not chunk:
                    return
                prefix += chunk
            request = prefix.split(b'\n', 1)[0].split()
            path, expires = self.server.registration
            if len(request) != 3 or request[1] != path or time.monotonic() >= expires:
                self.request.sendall(b'HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n')
                return
            with socket.create_connection(('127.0.0.1', self.server.forward_port), timeout=3) as target:
                self.request.settimeout(10)
                target.settimeout(10)
                target.sendall(prefix)
                with selectors.DefaultSelector() as io:
                    io.register(self.request, selectors.EVENT_READ, target)
                    io.register(target, selectors.EVENT_READ, self.request)
                    while events := io.select(timeout=25):
                        for key, _ in events:
                            data = key.fileobj.recv(64 * 1024)
                            if not data:
                                return
                            key.data.sendall(data)
        except OSError:
            pass
        finally:
            self.server.slots.release()


class Bridge(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    slots = threading.BoundedSemaphore(8)
    registration = (b'', 0.0)


class Discovery(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        session = re.fullmatch(r'/address\?session=([0-9a-f]{32})(?:&kind=(wallpaper|search))?', self.path)
        if not session:
            self.send_error(404)
            return
        kind = session.group(2) or 'wallpaper'
        expires = 310 if kind == 'search' else 610
        self.server.relay.registration = (f'/{kind}/{session.group(1)}'.encode('ascii'), time.monotonic() + expires)
        response = f'{self.server.endpoint}\n'.encode('ascii')
        self.send_response(200)
        self.send_header('Content-Type', 'text/plain')
        self.send_header('Content-Length', str(len(response)))
        self.end_headers()
        self.wfile.write(response)

    def log_message(self, *_):
        pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('adb')
    parser.add_argument('serial')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('bridge is for the local emulator only')
    adb = [args.adb, '-s', args.serial]
    if subprocess.check_output(adb + ['shell', 'getprop', 'ro.kernel.qemu'], text=True, timeout=5).strip() != '1':
        parser.error('target is not an emulator')
    host = lan_address()
    stop = threading.Event()
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, lambda *_: stop.set())
    with Bridge((host, LAN_PORT), Relay) as relay, http.server.ThreadingHTTPServer(('127.0.0.1', DISCOVERY_PORT), Discovery) as discovery:
        forward = subprocess.check_output(adb + ['forward', 'tcp:0', f'tcp:{LAN_PORT}'], text=True, timeout=5).strip()
        relay.forward_port = int(forward)
        try:
            discovery.endpoint = f'{host}:{LAN_PORT}'
            discovery.relay = relay
            threading.Thread(target=discovery.serve_forever, daemon=True).start()
            threading.Thread(target=relay.serve_forever, daemon=True).start()
            print('Wallpaper upload relay is ready', flush=True)
            while not stop.wait(10):
                if subprocess.run(adb + ['get-state'], capture_output=True, text=True, timeout=5).stdout.strip() != 'device':
                    break
        finally:
            relay.shutdown()
            discovery.shutdown()
            subprocess.run(adb + ['forward', '--remove', f'tcp:{forward}'], capture_output=True, timeout=5)


if __name__ == '__main__':
    main()
