#!/usr/bin/env python3
import time, csv, subprocess, sys, os

# --------------- 配置区域 ---------------
ALL_IPS = [
    "10.136.132.2", "10.140.8.2", "10.147.6.2", "10.135.134.2", "10.141.7.2",
    "10.138.5.2", "10.130.7.2", "10.131.132.2", "10.130.137.2", "10.131.1.2",
    "10.134.143.2", "10.132.10.2", "10.141.134.2", "10.136.7.2", "10.137.132.2",
    "10.132.132.2", "10.135.1.2", "10.143.10.2", "10.144.6.2", "10.137.8.2"
]

INTERVAL = 30            # 秒
DURATION_HOURS = 72      # 3 天
# --------------------------------------

if len(sys.argv) != 2:
    print("Usage: python3 latency_agent.py <my_ip>")
    sys.exit(1)

MY_IP = sys.argv[1]
OUTFILE = f"latency_{MY_IP.replace('.', '_')}.csv"

print(f"[INFO] Node {MY_IP} started. Measuring latency to {len(ALL_IPS)-1} peers.")
print(f"[INFO] Writing results to {OUTFILE}")

# 初始化 CSV
with open(OUTFILE, "w", newline="") as f:
    writer = csv.writer(f)
    writer.writerow(["timestamp", "src", "dst", "rtt_ms"])

end_time = time.time() + DURATION_HOURS * 3600

while time.time() < end_time:
    ts = int(time.time())
    for dst in ALL_IPS:
        if dst == MY_IP:
            continue
        try:
            # 仅发送一次 ping 请求，超时 1s
            result = subprocess.run(
                ["ping", "-c1", "-W1", dst],
                capture_output=True, text=True, timeout=2
            )
            rtt = None
            for line in result.stdout.splitlines():
                if "time=" in line:
                    rtt = line.split("time=")[-1].split()[0]
                    break
        except Exception:
            rtt = None

        with open(OUTFILE, "a", newline="") as f:
            writer = csv.writer(f)
            writer.writerow([ts, MY_IP, dst, rtt if rtt else "NaN"])

    time.sleep(INTERVAL)

print(f"[INFO] Node {MY_IP} finished measurement.")
