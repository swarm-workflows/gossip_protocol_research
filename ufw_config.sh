IP=192.5.87.18
# Allow all incoming traffic from 192.5.87.18
sudo iptables -A INPUT -s ${IP} -j ACCEPT

# Allow all outgoing traffic to 192.5.87.18
sudo iptables -A OUTPUT -d ${IP} -j ACCEPT

# If your system is configured as a router, you might also need to allow forwarded traffic from or to 192.5.87.18
sudo iptables -A FORWARD -s ${IP} -j ACCEPT
sudo iptables -A FORWARD -d ${IP} -j ACCEPT
sudo iptables -A INPUT -p tcp -j ACCEPT
sudo iptables -A INPUT -p udp -j ACCEPT