import re
from collections import defaultdict
import torch as th

K = 8
N = 100
latencies = [[], [], [], [], []]
pattern = re.compile(r'Server receives PHASE2BMESSAGE: (\d+), Endpoint: hostname: "(.*?)"\nport: (\d+)')
pattern_start = re.compile(r'Broadcast Start at (\d+), Endpoint: hostname: "(.*?)"\nport: (\d+)')
filename = [
			'N=100_random.txt',
			'N=100_random1.txt',
            'N=100_4DGRO.txt',
            'N=100_NN.txt',
            'N=100_NN+1DGRO.txt'
			
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
	time_by_endpoint = defaultdict(list)
    # Regular expression to extract time and endpoint information
	for i, line in enumerate(lines):
		if 'Borderline' in line or i == lineSize - 1:
			if baseport != -1:
				for endpoint, times in time_by_endpoint.items():
					if int(endpoint.split(':')[-1]) == baseport:
						continue
					# print(endpoint, times)
					latency = times[0] - start_time
					
					latencies[id].append(latency)
					if latency < 0:
						print(filename[id], endpoint, baseport, latency)
						print(times)
						print(time_by_endpoint[f'127.0.0.7:{baseport}'])
						assert 0
			if i == lineSize - 1:
				break
			baseport = int(line.split(' ')[-1][:-1])
			time_by_endpoint = defaultdict(list)

		# Extract matches and organize by endpoint
		match = re.match(pattern, line + lines[i + 1])
		if match:
			time, hostname, port = match.groups()
			endpoint = f"{hostname}:{port}"
			time_by_endpoint[endpoint].append(int(time))
			print(endpoint, time)
		match = re.match(pattern_start, line + lines[i + 1])
		if match:
			start_time, hostname, port = match.groups()
			start_time = int(start_time)
			baseport = int(port)
			# endpoint = f"{hostname}:{port}"
			# time_by_endpoint[endpoint].append(int(time))
		
        

import matplotlib.pyplot as plt

# Plot the sorted list
fig, ax = plt.subplots(ncols=1, figsize=(8, 8))
labels = ['random', 
		  'random1',
		    'DGRO-4',
			  'NN', 
			  'NN+1DGRO'
			  ]
colors = ['g', 'r', 'b', 'purple', 'k']
linestyles = ['-', '--', '-.']
cnt = 0
# for k in [3, 5, 10]:
for k in [8]:
	for i in range(len(filename)):
		print(len(latencies[i]))
		path_latency_sorted = sorted(latencies[i])
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