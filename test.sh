

#!/usr/bin/env zsh 
IP=127.0.0.1
java -jar examples/target/standalone-agent.jar --listenAddress $IP:1234 --seedAddress $IP:1234 > ~/tmp/rapid.1234 2>&1 &

batch_size=50  # 每批启动的进程数量
delay=60   # 每批启动之间的延迟时间，单位是秒
batch_count=0  # 初始化计数器

for each in $(seq 1235 1284); do
    java -jar examples/target/standalone-agent.jar --listenAddress $IP:$each --seedAddress $IP:1234 > ~/tmp/rapid.$each 2>&1 &
    # java -jar examples/target/standalone-agent.jar --listenAddress $IP:$each --seedAddress $IP:1234 > /dev/null 2>&1 &

    # # 每启动batch_size个进程，等待delay秒
    # ((batch_count++))

    # if (( batch_count % batch_size == 0 )); then
    #     echo "Waiting for $delay seconds before starting the next batch..."
    #     sleep $delay
    # fi
done