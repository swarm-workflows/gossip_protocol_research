import socket

def send_packet_udp(destination, port=37389):
    # Create a UDP socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    # Define the server address and port
    server_address = (destination, port)
    message = b'This is our test message.'

    try:
        # Send data
        print(f"Sending packet to {destination} port {port}")
        sent = sock.sendto(message, server_address)

        # Just for confirmation
        print(f"Sent {sent} bytes")

    finally:
        # Close the socket
        sock.close()

import socket

def send_packet_tcp(destination, port=37389):
    # Create a TCP socket
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)

    # Define the server address and port
    server_address = (destination, port)
    message = b'This is our test message.'

    try:
        # Connect to the server
        print(f"Connecting to {destination} on port {port}")
        sock.connect(server_address)

        # Send data
        print(f"Sending message to {destination} port {port}")
        sent = sock.sendall(message)  # sendall ensures all data is sent

        # Just for confirmation
        print(f"Message sent successfully")

    finally:
        # Close the socket
        sock.close()

if __name__ == '__main__':
    # Example usage: send a packet to localhost on port 9999
    send_packet_tcp('192.5.87.18', 1234)