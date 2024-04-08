

#! /bin/bash
IP=127.0.0.1
java -jar examples/target/standalone-agent.jar --listenAddress $IP:1234 --seedAddress $IP:1234 &> ./test_log/rapid.1234 &
for each in `seq 1235 1333`;
do
    java -jar examples/target/standalone-agent.jar --listenAddress $IP:$each --seedAddress $IP:1234 &> ./test_log/rapid.$each &
done
