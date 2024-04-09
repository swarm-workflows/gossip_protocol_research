import socket

def listen_for_packet():
    # Create a UDP socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    # Bind the socket to all available interfaces on port 37389
    server_address = ('', 37389)
    sock.bind(server_address)
    print("Listening on port 37389 for incoming packets...")

    try:
        # Wait for a single packet
        data, address = sock.recvfrom(4096)

        print(f"Received packet from {address}")
        print(f"Data: {data}")

    finally:
        # Close the socket
        sock.close()

if __name__ == '__main__':
    listen_for_packet()