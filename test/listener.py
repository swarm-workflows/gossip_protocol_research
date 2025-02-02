import socket

def listen_for_packet():
    # 创建一个可以处理 IPv4 & IPv6 的 UDP socket
    sock = socket.socket(socket.AF_INET6, socket.SOCK_DGRAM)
    
    # 允许同时监听 IPv4 和 IPv6
    sock.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)

    server_address = ('::', 1234)  # 监听所有 IPv6 地址
    sock.bind(server_address)

    try:
        # 接收数据包
        data, address = sock.recvfrom(4096)
        print(f"Received packet from {address}")
        print(f"Data: {data}")
    finally:
        sock.close()

if __name__ == '__main__':
    listen_for_packet()
