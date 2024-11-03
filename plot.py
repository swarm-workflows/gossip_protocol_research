#!/usr/bin/env python3

import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.dates as mdates
import datetime

# 读取数据
data = pd.read_csv('system_monitor.csv')

# 将时间列转换为 datetime 对象
data['时间'] = pd.to_datetime(data['时间'])

# 设置时间为索引
data.set_index('时间', inplace=True)

# 创建一个包含所有子图的图形
fig, axs = plt.subplots(4, 1, figsize=(12, 10), sharex=True)

# 绘制 CPU 使用率
axs[0].plot(data.index, data['CPU使用率(%)'], color='blue')
axs[0].set_ylabel('CPU(%)')
axs[0].grid(True)

# 绘制内存使用情况
axs[1].plot(data.index, data['内存使用(MB)'], color='green')
axs[1].set_ylabel('Memory(MB)')
axs[1].grid(True)

# 绘制磁盘 I/O
axs[2].plot(data.index, data['磁盘读(KB/s)'], label='磁盘读(KB/s)', color='red')
axs[2].plot(data.index, data['磁盘写(KB/s)'], label='磁盘写(KB/s)', color='orange')
axs[2].set_ylabel('Disk I/O (KB/s)')
axs[2].legend()
axs[2].grid(True)

# 绘制网络带宽
axs[3].plot(data.index, data['网络收(KB/s)'], label='网络收(KB/s)', color='purple')
axs[3].plot(data.index, data['网络发(KB/s)'], label='网络发(KB/s)', color='brown')
axs[3].set_ylabel('Network Bandwidth (KB/s)')
axs[3].legend()
axs[3].grid(True)

# 设置时间格式
axs[3].xaxis.set_major_formatter(mdates.DateFormatter('%H:%M:%S'))

# 设置 X 轴标签
axs[3].set_xlabel('Time')

# 自动调整日期标签显示
fig.autofmt_xdate()

# 设置整体标题
fig.suptitle('System Resource Monitor')

# 调整子图间距
plt.tight_layout(rect=[0, 0.03, 1, 0.95])

# 显示图形
plt.savefig("monitor.png")
