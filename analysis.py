import matplotlib.pyplot as plt
import os
import torch as th

def read_files_in_folder(folder_path):
    # 确保提供的路径存在
    if not os.path.exists(folder_path):
        print(f"Folder '{folder_path}' does not exist.")
        return
    
    x = []
    y = []
    adjacency_graph = {}
    # 遍历文件夹中的所有文件
    for root, dirs, files in os.walk(folder_path):
        # print(len(files))
        files.sort()
        # assert 0
        for file in files:
            file_path = os.path.join(root, file)
            # print(f"Reading {file_path}...")
           
            # adjacency_graph[int(file_path[-4:]) - 1234]  = []
            cnt = 0
            min_t = 100000000
            max_t = -1
            with open(file_path, 'r', encoding='utf-8') as file:
                for line in file:
                    # if "port: " in line:
                    #     adjacency_graph[int(file_path[-4:]) - 1234].append(int(line[6:]) - 1234)
                    if 'message_size=21' in line:
                        cnt += 21
                    if 't=2024-03-19 ' in line:
                        t =  time = line[-8:]
                        time = time.split(':')
                        time = int(time[0]) * 3600 + int(time[1]) * 60 + int(time[2])
                        min_t = min(min_t, time)
                        max_t = max(max_t, time)
            print(int(file_path[-4:]), cnt / (max_t - min_t))
    for key, value in adjacency_graph.items():
        adjacency_graph[key] = list(set(value))
    print(adjacency_graph)
    # return x, y

read_files_in_folder(f"test_log")
