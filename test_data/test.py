import re
from collections import defaultdict
import torch as th

K = 3
N = 50
latencies = th.zeros(4, N, N)
pattern = re.compile(r'当前时间（毫秒精度）: (\d+), Endpoint: hostname: "(.*?)"\nport: (\d+)')
filename = [
			#  "N=50_K=3_DGRO=0_1.txt",
			#  "N=50_K=3_DGRO=1.txt",
			#  "N=50_K=3_DGRO=2.txt",
			#  "N=50_K=3_DGRO=3.txt"
			'output_DGRO=0',
			'output_DGRO=1',
			'output_DGRO=2',
			'output_DGRO=3',
			# 'output_DGRO=0_N=100',
			# 'output_DGRO=1_N=100',
			# 'output_DGRO=2_N=100',
			# 'output_DGRO=3_N=100'
			# 'DGRO=0_N=100_Sample=100'
			
			 ]
	
# for K in [3, 5, 10]:
# for K in [3]:
	# filename = [f"test_data/N={N}_1/random_K={K}.txt", f"test_data/N={N}_1/1shortest_K={K}.txt"]
for id in range(len(filename)):
	with open(filename[id]) as f:
		lines = f.readlines()
		lineSize = len(lines)
		# lines = f.
	baseport = -1
	# Regular expression to extract time and endpoint information
	for i, line in enumerate(lines):
		if 'Borderline' in line or i == lineSize - 1:
			if baseport != -1:
				for endpoint, times in time_by_endpoint.items():
					if int(endpoint.split(':')[-1]) == baseport:
						continue
					latency = times[0] - time_by_endpoint[f'127.0.0.7:{baseport}'][2]
					
					latencies[id][baseport - 1234][int(endpoint.split(':')[-1]) - 1234] = latency
					# print(endpoint, baseport, latency)
					if latency < 0:
						# latencies[id][baseport - 1234][int(endpoint.split(':')[-1]) - 1234] = latency
						print(filename[id], endpoint, baseport, latency)
						print(times)
						print(time_by_endpoint[f'127.0.0.7:{baseport}'])
						assert 0
			# print(line.split(' '))
			if i == lineSize - 1:
				break
			baseport = int(line.split(' ')[-1][:-1])

				# Dictionary to store times by endpoint
			# print(baseport)
			time_by_endpoint = defaultdict(list)

		# Extract matches and organize by endpoint
		for match in pattern.finditer(line + lines[i + 1]):
			time, hostname, port = match.groups()
			endpoint = f"{hostname}:{port}"
			time_by_endpoint[endpoint].append(int(time))

		# Print the results
		# for endpoint, times in time_by_endpoint.items():
		#     print(f"Endpoint: {endpoint}")
		#     print(f"Times: {times}\n")


import matplotlib.pyplot as plt

# Plot the sorted list
fig, ax = plt.subplots(ncols=1, figsize=(8, 8))
labels = ['random', 'DGRO-1', 'DGRO-2', 'DGRO-3']
colors = ['g', 'r', 'b', 'purple']
linestyles = ['-', '--', '-.']
cnt = 0
# for k in [3, 5, 10]:
for k in [3]:
	for i in range(len(filename)):
		
		path_latency_sorted = sorted(latencies[i].flatten())
		# print(path_latency_sorted[:5])
		print(path_latency_sorted[:5], path_latency_sorted[-5:])
		ax.plot(path_latency_sorted, linestyle=linestyles[0], 
		  label=labels[i]+f'_FanOut={k}_N={N}',
		#   label=filename[i],
		    color=colors[i])
		# cnt += 1
ax.set_title('Sorted Path Latency')
ax.set_xlabel('Index')
ax.set_ylabel('Latency (ms)')
ax.legend()
ax.grid(True)
# plt.show()
fig.savefig(f'N={N}_k={K}.png')