import re
from collections import defaultdict
path_letencies = []
K = 3
N = 100
# for K in [3, 5, 10]:
for K in [3]:
	# filename = [f"test_data/N={N}_1/random_K={K}.txt", f"test_data/N={N}_1/1shortest_K={K}.txt"]
	filename = [
			 "N=50_K=3_DGRO=0.txt",
			 "N=50_K=3_DGRO=1.txt",
			 "N=50_K=3_DGRO=2.txt",
			 "N=50_K=3_DGRO=3.txt"
			 ]
	for i in range(4):
		with open(filename[i]) as f:
			data = f.read()
		# Regular expression to extract time and endpoint information
		pattern = re.compile(r'当前时间（毫秒精度）: (\d+), Endpoint: hostname: "(.*?)"\nport: (\d+)')

		# Dictionary to store times by endpoint
		time_by_endpoint = defaultdict(list)

		# Extract matches and organize by endpoint
		for match in pattern.finditer(data):
			time, hostname, port = match.groups()
			endpoint = f"{hostname}:{port}"
			time_by_endpoint[endpoint].append(int(time))

		# Print the results
		# for endpoint, times in time_by_endpoint.items():
		#     print(f"Endpoint: {endpoint}")
		#     print(f"Times: {times}\n")

		path_latency = []
		for endpoint, times in time_by_endpoint.items():
			path_latency.append(times[0] - time_by_endpoint['127.0.0.7:1234'][0])
			if path_latency[-1] < 0:
				print(filename[i], endpoint, times)
				print(times[0], time_by_endpoint['127.0.0.7:1234'][0])
				assert 0
		path_letencies.append(path_latency)

import matplotlib.pyplot as plt

# Plot the sorted list
fig, ax = plt.subplots(ncols=1, figsize=(8, 8))
labels = ['random', 'DGRO-1', 'DGRO-2', 'DGRO-3']
colors = ['g', 'r', 'b', 'purple']
linestyles = ['-', '--', '-.']
cnt = 0
# for k in [3, 5, 10]:
for k in [3]:
	for i in range(4):
		
		path_latency_sorted = sorted(path_letencies[i])
		# print(path_latency_sorted[:5])
		print(path_latency_sorted)
		ax.plot(path_latency_sorted, linestyle=linestyles[0], label=labels[i]+f'_FanOut={k}_N=100', color=colors[i])
		# cnt += 1
ax.set_title('Sorted Path Latency')
ax.set_xlabel('Index')
ax.set_ylabel('Latency (ms)')
ax.legend()
ax.grid(True)
# plt.show()
fig.savefig('2.png')