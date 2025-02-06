import os

# Directory path
base_dir = os.path.expanduser("~/tmp")

# File range
start_index = 1234
end_index = 1284

# Phrase to search for
target_phrase = "Proposing membership change of size 1"

# Iterate through the file range
for i in range(start_index, end_index + 1):
    file_path = os.path.join(base_dir, f"rapid.{i}")
    
    if os.path.exists(file_path):
        try:
            with open(file_path, "r") as file:
                for line in file:
                    if target_phrase in line:
                        print(f"Found in {file_path}")
                        break  # No need to read further in this file
        except Exception as e:
            print(f"Error reading {file_path}: {e}")
    else:
        print(f"File not found: {file_path}")