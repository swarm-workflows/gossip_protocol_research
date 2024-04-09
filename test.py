import subprocess

# Define the IP address and base port
ip_address = "192.168.100.228"
base_port = 1234
log_directory = "./test_log"
n_proc = 80
start_port = 1235
end_port = base_port + n_proc

# Ensure the log directory exists
subprocess.run(["rm", "-r", log_directory], check=True)
subprocess.run(["mkdir", "-p", log_directory], check=True)

# Start the first agent process and redirect its output to a log file
with open(f"{log_directory}/rapid.{base_port}", "w") as logfile:
    subprocess.Popen([
        "java", "-jar", "examples/target/standalone-agent.jar",
        "--listenAddress", f"{ip_address}:{base_port}",
        "--seedAddress", f"{ip_address}:{base_port}"
    ], stdout=logfile, stderr=subprocess.STDOUT)

# Loop to start agents on ports from start_port to end_port
for port in range(start_port, end_port):
    with open(f"{log_directory}/rapid.{port}", "w") as logfile:
        subprocess.Popen([
            "java", "-jar", "examples/target/standalone-agent.jar",
            "--listenAddress", f"{ip_address}:{port}",
            "--seedAddress", f"{ip_address}:{base_port}"
        ], stdout=logfile, stderr=subprocess.STDOUT)

print("Commands executed successfully.")
