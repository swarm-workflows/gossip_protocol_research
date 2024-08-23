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

# 389  sudo firewall-cmd --zone=public --add-service http
# 390  sudo firewall-cmd --zone=public --add-service https
# 391  sudo firewall-cmd --zone=public --add-service tcp
# 392  sudo firewall-cmd --zone=public --add-service TCP
# 393  sudo firewall-cmd --zone=public --add-port=12345/tcp --permanent
# 394  sudo firewall-cmd --zone=public --add-port=23/tcp
# 395  udo firewall-cmd --zone=public --add-service telnet
# 396  sudo firewall-cmd --zone=public --add-service telnet
# 401  sudo firewall-cmd --zone=public --list-ports
# 402  sudo firewall-cmd --zone=public --add-port=9001/tcp
# 403  sudo firewall-cmd --zone=public --add-port=37389/tcp
# 406  sudo firewall-cmd --zone=public --list-ports
# 407  sudo firewall-cmd --zone=public --add-port=37389/udp
# 408  sudo firewall-cmd --zone=public --list-ports
# 410  sudo firewall-cmd --zone=public --add-port=1234/udp
# 411  sudo firewall-cmd --zone=public --add-port=1234/tcp
# 414  sudo firewall-cmd --zone=public --list-ports
# 417  sudo firewall-cmd --zone=public --add-service tcp
# 418  sudo firewall-cmd --zone=public --add-service TCP
# 419  sudo firewall-cmd --zone=public --list-ports
# 431  history | grep firewall-cmd
# 432  sudo firewall-cmd --permanent --zone=public --add-service=http
# 433  sudo firewall-cmd --permanent --zone=public --add-service=https
# 434  sudo firewall-cmd --permanent --zone=public --add-service=http
# 435  sudo firewall-cmd --permanent --zone=public --add-service=dns
# 437  sudo firewall-cmd --reload
# 438  sudo firewall-cmd --zone=public --list-ports
# 442  sudo firewall-cmd --zone=public --add-port=1234/tcp
# 443  sudo firewall-cmd --zone=public --add-port=1234/udp
# 445  sudo firewall-cmd --zone=public --add-port=1234/udp
# 460  sudo firewall-cmd --zone=public --add-port=1234-10086/tcp --permanent --
# 461  sudo firewall-cmd --zone=public --add-port=1234-10086/udp --permanent