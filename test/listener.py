import socket

def listen_for_packet_udp(port=37389):
    # Create a UDP socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    # Bind the socket to all available interfaces on port 37389
    server_address = ('', port)
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

def listen_for_packet_tcp(port=37389):
    # Create a TCP socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)

    # Bind the socket to all available interfaces on port 37389
    server_address = ('', port)
    sock.bind(server_address)

    # Start listening for incoming connections
    sock.listen(1)
    print(f"Listening on port {port} for incoming connections...")

    try:
        # Wait for a connection
        connection, client_address = sock.accept()

        try:
            print(f"Connection from {client_address}")

            # Receive data in small chunks
            data = connection.recv(4096)
            print(f"Data: {data}")

        finally:
            # Close the connection
            connection.close()

    finally:
        # Close the socket
        sock.close()

if __name__ == '__main__':
    listen_for_packet_tcp(port=1235)