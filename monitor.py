#!/usr/bin/env python3

import psutil
import time
import datetime
import csv

# 输出文件名
output_file = 'system_monitor.csv'

# 监控间隔（秒）
interval = 5

def get_network_io(prev_net_io):
    net_io = psutil.net_io_counters()
    bytes_sent = net_io.bytes_sent - prev_net_io.bytes_sent
    bytes_recv = net_io.bytes_recv - prev_net_io.bytes_recv
    # 转换为KB/s
    bytes_sent_per_sec = bytes_sent / 1024 / interval
    bytes_recv_per_sec = bytes_recv / 1024 / interval
    return bytes_sent_per_sec, bytes_recv_per_sec, net_io

def main():
    # 初始化网络I/O
    prev_net_io = psutil.net_io_counters()

    # 如果文件不存在，写入标题行
    with open(output_file, 'w', newline='') as csvfile:
        fieldnames = ['时间', 'CPU使用率(%)', '内存使用(MB)', '磁盘读(KB/s)', '磁盘写(KB/s)', '网络收(KB/s)', '网络发(KB/s)']
        writer = csv.DictWriter(csvfile, fieldnames=fieldnames)
        writer.writeheader()

    print(f"开始监控系统资源，每隔 {interval} 秒输出一次数据到 {output_file}。按 Ctrl+C 停止。")

    try:
        while True:
            current_time = datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')

            # CPU使用率
            cpu_usage = psutil.cpu_percent(interval=None)

            # 内存使用情况
            mem = psutil.virtual_memory()
            mem_usage = (mem.total - mem.available) / 1024 / 1024  # 转换为MB

            # 磁盘I/O
            disk_io1 = psutil.disk_io_counters()
            time.sleep(interval)
            disk_io2 = psutil.disk_io_counters()
            disk_read = (disk_io2.read_bytes - disk_io1.read_bytes) / 1024 / interval  # KB/s
            disk_write = (disk_io2.write_bytes - disk_io1.write_bytes) / 1024 / interval  # KB/s

            # 网络I/O
            net_sent_per_sec, net_recv_per_sec, prev_net_io = get_network_io(prev_net_io)

            # 将数据写入CSV文件
            with open(output_file, 'a', newline='') as csvfile:
                writer = csv.DictWriter(csvfile, fieldnames=fieldnames)
                writer.writerow({
                    '时间': current_time,
                    'CPU使用率(%)': cpu_usage,
                    '内存使用(MB)': mem_usage,
                    '磁盘读(KB/s)': disk_read,
                    '磁盘写(KB/s)': disk_write,
                    '网络收(KB/s)': net_recv_per_sec,
                    '网络发(KB/s)': net_sent_per_sec
                })

    except KeyboardInterrupt:
        print("监控已停止。")

if __name__ == '__main__':
    main()
