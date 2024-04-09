import socket

def send_packet(destination, port):
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

if __name__ == '__main__':
    # Example usage: send a packet to localhost on port 9999
    send_packet('128.55.64.43', 37389)