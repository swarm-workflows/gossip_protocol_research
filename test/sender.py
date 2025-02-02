import socket

def send_packet(destination, port):
    # Determine if the destination is IPv4 or IPv6
    family = socket.AF_INET6 if ':' in destination else socket.AF_INET

    # Create a UDP socket
    sock = socket.socket(family, socket.SOCK_DGRAM)

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
    # Example usage: send a packet to an IPv6 address on port 37389
    send_packet('2605:2800:2011:201:f816:3eff:fe62:483c', 37389)