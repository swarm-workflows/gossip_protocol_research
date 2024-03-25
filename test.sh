#! /bin/bash
# java -jar examples/target/standalone-agent.jar --listenAddress 127.0.0.7:1234 --seedAddress 127.0.0.7:1234 &> ./test_log/rapid.1234 &
IP=128.55.78.96
java -jar examples/target/standalone-agent.jar --listenAddress $IP:1234 --seedAddress $IP:1234 &> ./test_log/rapid.1234 &
for each in `seq 1235 1333`;
do
    java -jar examples/target/standalone-agent.jar --listenAddress $IP:$each --seedAddress $IP:1234 &> ./test_log/rapid.$each &
done
# loginIP=128.55.84.126
# for each in `seq 1334 1433`;
# do
#     java -jar examples/target/standalone-agent.jar --listenAddress $loginIP:$each --seedAddress $IP:$((each-100)) &> ./test_log/rapid.$each &
# done
