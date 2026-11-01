"""SSH over WebSocket for the emulator test.

Answers the HTTP upgrade on GET /ssh, then passes the connection to the SSH server
as it is, like the SSH-over-WebSocket servers people buy accounts on.

usage: ws-ssh.py <listen port> <ssh port>
"""
import socket
import sys
import threading

LISTEN_PORT = int(sys.argv[1])
SSH_PORT = int(sys.argv[2])


def pipe(source, target):
    try:
        while True:
            data = source.recv(65536)
            if not data:
                break
            target.sendall(data)
    except OSError:
        pass
    finally:
        for s in (source, target):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def handle(client):
    request = b""
    while b"\r\n\r\n" not in request:
        chunk = client.recv(4096)
        if not chunk or len(request) > 16384:
            client.close()
            return
        request += chunk
    head, _, rest = request.partition(b"\r\n\r\n")
    text = head.decode("latin-1")
    if not text.startswith("GET /ssh ") or "upgrade: websocket" not in text.lower():
        client.sendall(b"HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n")
        client.close()
        return
    client.sendall(b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n")
    upstream = socket.create_connection(("127.0.0.1", SSH_PORT))
    if rest:
        upstream.sendall(rest)
    threading.Thread(target=pipe, args=(client, upstream), daemon=True).start()
    pipe(upstream, client)


server = socket.socket()
server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
server.bind(("0.0.0.0", LISTEN_PORT))
server.listen(64)
while True:
    connection, _ = server.accept()
    threading.Thread(target=handle, args=(connection,), daemon=True).start()
