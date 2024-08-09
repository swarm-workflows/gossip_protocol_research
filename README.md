gossip_protocol_research
===================
# Swarm agents communicate  with other agents and with resources through a network optimized to provide minimum latency by generatin overlay topologies. In this optimization, we start with a membership protocol~(Gossip) based on \( d \) Hamilton Cycles and test a greedy approach on a widely-used dataset from the bitcoin/blockchain community. We observe that even a simple greedy algorithm outperforms RAPID, CHORD, Nearest Neighbor, and Perigee methods~(state of the art). We further improve our greedy approach with a Graph Neural Network (GNN)-based Q-learning method.

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
