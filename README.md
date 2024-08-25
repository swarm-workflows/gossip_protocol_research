Gossip Protocol Research
===================
# About

- **Swarm Communication**: Agents communicate with peers and resources via a network designed for minimal latency, utilizing overlay topologies.
- **Optimization Strategy**: Starts with a [membership protocol](#References) using Gossip based on multiple Hamilton Cycles.
- **Algorithm Testing**: Employs a greedy algorithm tested against a popular dataset from the bitcoin/blockchain community.
- **Performance Comparison**: Demonstrates that the simple greedy algorithm surpasses other methods like RAPID, CHORD, Nearest Neighbor, and Perigee.
- **Advanced Improvements**: Enhances the greedy algorithm with a Graph Neural Network (GNN)-based Q-learning method for further optimization.

<img src="diameter.png" width="800">

# Installation
Run the following command to set up the codes
```
bash install.sh
```

On Chameleon we need to allow all incoming traffic from the nodes on the subnet. To enable this use the followin command on the nodes.
```
sudo iptables -A INPUT -s 192.168.100.0/24 -j ACCEPT
```

Run the following command to launch 100 RAPID processes locally, at port 1234 to port 1333. The standard output of each process will be directed to test_log/$pid.log, where $pid is the PID of the process.

```
mkdir test_log
./test.sh
```

#### References
[1] Suresh, Lalith, et al. "Stable and consistent membership at scale with rapid." 2018 USENIX Annual Technical Conference (USENIX ATC 18). 2018.

# ToDo:
## Aug 23 2024 
- Assuming two processes run under the same node/subnet, does using public IP as destination introduce overhead?

- Replace subnet ip with float ip (Finished, Aug 24)
    - RAPID include the local listen address inside the out-going packet, result in unreachbility back from receiver. 
    - Discover its own public ip.
    - Implement an input for floating ip.
    - Replace subnet ip with float ip within the out-going message.

## Aug 24 2024
- Public IP introduces failure on retries. Need to figure out:
    - Observation:
        - Failure Location: Retries.java 73
        - Failure Type: Unavailable or Timeout 
        - Errors only occur in seedAddress
        - Cluster size is correct.
    - Why connection fails but cluster size is correct? Unavailable or Timeout
    - Solved: firewall-cmd should not include --permanent
        - `sudo firewall-cmd --zone=public --add-port=1234-10086/tcp`
        - `sudo firewall-cmd --zone=public --add-port=1234-10086/udp`

- Add time out to simulate latency
    - Sender: 
        - add timestamp to each message
    - Receiver:
        - Get current time t_receive
        - Get timestamp from message t_send
        - Get time variant delta_t = t_receive - t_send
        - get minimum latency l=  latency(send, rec)
        - if delta_t > l:
            do nothing, pass
        - else:
            sleep for (l - delta_t)
            pass
    - Seems solved with scheduler. Need to verify with synchroinization measurement.

- Measure synchronization / broadcast time.
    - 

