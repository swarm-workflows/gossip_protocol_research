import subprocess
from scapy.all import sniff
from collections import defaultdict
import threading
import socket
import time
import psutil
base_port = 1234
n_proc = 300
port_bytes = defaultdict(int)

def monitor_ports(packet):
    """Process packets to calculate bytes per port."""
    if packet.haslayer('TCP') or packet.haslayer('UDP'):
        src_port = packet.sport
        dst_port = packet.dport
        # Update bytes for source and destination ports if in range
        if base_port <= src_port < base_port + n_proc:
            port_bytes[src_port] += len(packet)
        if base_port <= dst_port < base_port + n_proc:
            port_bytes[dst_port] += len(packet)

def monitor_process(pid, file):
    try:
        p = psutil.Process(pid)
        with p.oneshot():
            # Get the name of the process and its current status
            name = p.name()
            status = p.status()

            # Get CPU usage percentage without specific interval for immediate, non-blocking call
            cpu_usage = p.cpu_percent(interval=None)  # Immediate, non-blocking call

            # Get memory utilization
            memory_info = p.memory_full_info()
            memory_usage = memory_info.uss  # USS memory (Unique Set Size)

            # Get disk I/O information (since the last call, need to call this periodically)
            io_counters = p.io_counters()
            read_bytes = io_counters.read_bytes
            write_bytes = io_counters.write_bytes
            # net_io = psutil.net_io_counters(pernic=True)
            # Timestamp for the log entry
            timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
            
            # Write data to file
            file.write(f"Timestamp: {timestamp}, Process: {name}, PID: {pid}, Status: {status}\n")
            file.write(f"CPU Usage: {cpu_usage}%, Memory Usage: {memory_usage / (1024**2)} MB\n")
            file.write(f"Disk Read: {read_bytes / (1024**2)} MB, Disk Write: {write_bytes / (1024**2)} MB\n")
            # for nic, counters in net_io.items():
            #     file.write(f"  {nic} - Sent: {counters.bytes_sent / (1024**2)} MB, Received: {counters.bytes_recv / (1024**2)} MB")
            file.write("\n")
    except psutil.NoSuchProcess:
        file.write(f"Timestamp: {time.strftime('%Y-%m-%d %H:%M:%S')}, No process found with PID: {pid}\n")

def print_bandwidth(file):
    """Print bandwidth usage per port and reset counters."""
    for port in range(base_port, base_port + n_proc):
        if port in port_bytes:
            file.write(f"Port {port}: {port_bytes[port]} bytes\n")
    # Reset port bytes after reporting
    port_bytes.clear()

def monitor(java_pids, lock):
    # Assuming Java processes are still running and you have their PIDs
    # For example, we assume some PIDs like [1234, 1235, 1236] just for demonstration
    # java_pids = [1234, 1235, 1236]
    # Open a file to write the monitoring data
    try:
        disk_io_old = psutil.disk_io_counters()
        while True :
            # lock.acquire()  # Acquire the lock before reading the list
            # try:
            #     current_pids = list(java_pids)  # Make a local copy while holding the lock
            # finally:
            #     lock.release()  # Release the lock
            # if len(current_pids) != 0:
            #     for i in range(0, len(current_pids), (len(current_pids) + 4) // 5):
            #         pid = current_pids[i]
            #         monitor_process(pid, file)
            cpu_usage = psutil.cpu_percent(interval=None)
            # Memory utilization
            memory = psutil.virtual_memory()
            memory_usage = memory.percent
            disk_io = psutil.disk_io_counters()
            read_bytes = disk_io.read_bytes - disk_io_old.read_bytes
            write_bytes = disk_io.write_bytes - disk_io_old.write_bytes
            disk_io_old = disk_io
            timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
            # Write data to file
            with open("process_monitoring_log.txt", "a") as file:
                file.write(f"Timestamp: {timestamp}\n")
                file.write(f"CPU Usage: {cpu_usage}%, Memory Usage: {memory_usage}%\n")
                file.write(f"Disk Read: {read_bytes / (1024**2)} MB, Disk Write: {write_bytes / (1024**2)} MB\n")
                file.write("\n")      
                print_bandwidth(file)  
                file.flush()
            time.sleep(1)
    except KeyboardInterrupt:
        print("Monitoring stopped.")

def run_sniffer(base_port, n_proc):
    port_range = f"tcp or udp portrange {base_port}-{base_port + n_proc - 1}"
    sniff(iface="lo", filter=port_range, store=False, prn=monitor_ports)

if __name__ == '__main__':
    # Define the IP address and base port
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.connect(("8.8.8.8", 80))
    ip_address = s.getsockname()[0]
    s.close()
    # sd_address = "192.168.100.228"
    sd_address = ip_address
    
    log_directory = "./test_log"
    
    end_port = base_port + n_proc

    java_pids = []
    lock = threading.Lock()
    

    # Thread for monitoring
    # monitoring_thread = threading.Thread(target=monitor, args=(java_pids, lock))
    # monitoring_thread.start()

    # sniffer_thread = threading.Thread(target=run_sniffer, args=(base_port, n_proc))
    # sniffer_thread.start()

    # Ensure the log directory exists
    subprocess.run(["rm", "-r", log_directory], check=True)
    subprocess.run(["mkdir", "-p", log_directory], check=True)

    # threading.Thread(target=monitor, args=(java_pids, lock))
    # sniff(filter=f"tcp or udp portrange {base_port}-{base_port + n_proc - 1}", store=False, prn=monitor_ports)
    # Start the first agent process and redirect its output to a log file
    with open(f"{log_directory}/rapid.{base_port}", "w") as logfile:
        process = subprocess.Popen([
            "java", "-jar", "examples/target/standalone-agent.jar",
            "--listenAddress", f"{ip_address}:{base_port}",
            "--seedAddress", f"{sd_address}:{base_port}"
        ], stdout=logfile, stderr=subprocess.STDOUT)
        lock.acquire()
        java_pids.append(process.pid)
        lock.release()
    time.sleep(5)
    # Loop to start agents on ports from start_port to end_port
    for port in range(base_port + 1, end_port):
        with open(f"{log_directory}/rapid.{port}", "w") as logfile:
            process = subprocess.Popen([
                "java", "-jar", "examples/target/standalone-agent.jar",
                "--listenAddress", f"{ip_address}:{port}",
                "--seedAddress", f"{ip_address}:{base_port}"
            ], stdout=logfile, stderr=subprocess.STDOUT)
            # lock.acquire()
            # java_pids.append(process.pid)
            # lock.release()
            cpu_usage = psutil.cpu_percent(interval=None)
            memory = psutil.virtual_memory()
            memory_usage = memory.percent
            timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
            print(f"Timestamp: {timestamp}\n")
            print((f"CPU Usage: {cpu_usage}%, Memory Usage: {memory_usage}%\n"))
            time.sleep(2)



    print("Commands executed successfully.")
