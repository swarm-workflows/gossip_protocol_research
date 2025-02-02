import socket

def listen_for_tcp():
    # 创建 IPv6 TCP socket
    sock = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)

    # 允许同时监听 IPv4 和 IPv6
    sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)

    server_address = ('::', 1234)  # 监听所有 IPv6 地址
    sock.bind(server_address)
    sock.listen(5)  # 允许最多 5 个连接排队

    print("Listening for TCP connections on port 1234...")
    while True:
        connection, address = sock.accept()  # 接收 TCP 连接
        print(f"Received connection from {address}")
        data = connection.recv(4096)
        print(f"Data received: {data}")
        connection.close()

if __name__ == '__main__':
    listen_for_tcp()
