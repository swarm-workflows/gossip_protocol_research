Rapid
===================

Clone this repository and install rapid into your local maven repository:
Download and install maven-3.5.3 using the following link:
```
wget https://archive.apache.org/dist/maven/maven-3/3.5.3/binaries/apache-maven-3.5.3-bin.tar.gz
tar -xzvf apache-maven-3.5.3-bin.tar.gz
git clone 
```

```shell
   $: mvn install
```

If your project uses maven, add the following dependency into your project's pom.xml:

```xml
  <dependency>
     <groupId>com.github.lalithsuresh</groupId>
     <artifactId>rapid</artifactId>
     <version>0.8.0</version>
  </dependency>
```

For a simple example project that uses Rapid's APIs, see `examples/`.


Running Rapid
=============

For the following steps, ensure that you've built or installed Rapid:

```shell
  $: mvn package  # or mvn install
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


To run the `AgentWithNettyMessaging` example, replace `standalone-agent.jar`
in the above commands with `netty-based-agent.jar`.
