gossip_protocol_research
===================

Run the following command to set up the codes
```
wget https://archive.apache.org/dist/maven/maven-3/3.5.3/binaries/apache-maven-3.5.3-bin.tar.gz
tar -xzvf apache-maven-3.5.3-bin.tar.gz
cd apache-maven-3.5.3-bin.tar.gz
git clone https://github.com/swarm-workflows/gossip_protocol_research.git
cd gossip_protocol_research
mvn install
```

Run the following command to launch 100 RAPID processes locally, at port 1234 to port 1333. The standard output of each process will be directed to test_log/$pid.log, where $pid is the PID of the process.
```
mkdir test_log
./test.sh
```