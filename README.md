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

To launch a simple Rapid-based agent, run the following commands in your shell
from the top-level directory:

```shell
  $: java -jar examples/target/standalone-agent.jar \ 
          --listenAddress 127.0.0.7:1234 \
          --seedAddress 127.0.0.7:1234
```

From two other terminals, try adding a few more nodes on different listening
addresses, but using the same seed address of "127.0.0.7:1234". For example:

```shell
  $: java -jar examples/target/standalone-agent.jar \ 
          --listenAddress 127.0.0.7:1235 \
          --seedAddress 127.0.0.7:1234

  $: java -jar examples/target/standalone-agent.jar \
          --listenAddress 127.0.0.7:1236 \
          --seedAddress 127.0.0.7:1234
```

Or use the following script to start multiple agents in the background that
bootstrap via node 127.0.0.7:1234.

```bash
  #! /bin/bash
  for each in `seq 1235 1245`;
  do
        java -jar examples/target/standalone-agent.jar \
             --listenAddress 127.0.0.7:$each \
             --seedAddress 127.0.0.7:1234 &> /tmp/rapid.$each &
  done
```