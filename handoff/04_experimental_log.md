# 04 — Experimental Details & Artifact Locations

## Datasets

### FABRIC 400-Node Graph

- **File:** `TetriX/sc_test/G_400_FABRIC.pkl`
- **Format:** NetworkX `Graph` object with `weight` attributes on every edge
- **Weights:** RTT latency (ms) between node pairs, measured on FABRIC
- **Raw data:** `TetriX/sc_test/FABRIC_400.json`
- **Collection:** Latency probe messages sent between all 400 processes; median RTT recorded

### FABRIC 100-Node Graph

- **File:** `TetriX/sc_test/G_100_FABRIC.pkl`
- **Use:** Quick experiments, debugging; same format as 400-node version

### Saved Topologies

- `TetriX/sc_test/best_test_graph.pkl` — current best (overwritten each training run)
- `TetriX/sc_test/best_test_graph_*.pkl` — milestone snapshots

To inspect a saved graph:

```python
import pickle as pkl
import networkx as nx

with open('TetriX/sc_test/best_test_graph.pkl', 'rb') as f:
    G = pkl.load(f)

print(G.number_of_nodes(), G.number_of_edges())
print(nx.diameter(G))
```

---

## Trained Models (NERSC pscratch)

Location: `/pscratch/sd/s/swu264/SWARM/model/`

Each run creates a timestamped directory:

```
/pscratch/sd/s/swu264/SWARM/model/
└── 20240315_143022/         # example timestamp
    ├── config.json          # exact args for this run
    ├── output               # log: reward, loss, test_diameter per epoch
    └── *.pth                # PyTorch checkpoint
```

**Important:** pscratch is purged after ~30 days. Any checkpoint worth keeping must be copied to `$HOME` or `$CFS`.

---

## Key Experiments and Their Parameters

### Main DGRO Experiment (SC paper)

```
N=400, K=3, num_sources=2, feature_dim=4, lr=5e-4, bs=64, seed=1
Graph: G_400_FABRIC.pkl
Reward: prev_diameter - cur_diameter - edge_weight
```

### Parallel DGRO (preliminary)

```
M=4 partitions, alpha anneals 0.5 → 0.8
Saves to: /pscratch/sd/s/swu264/SWARM/model/parallel_dgro_<timestamp>/
Script: TetriX/latency/parallel/train_parallel_improved.py
```

### Diameter Approximation Ablation

Controlled by `--num_sources`:
- `num_sources=1`: fastest, least accurate
- `num_sources=2`: default (good balance)
- `num_sources=N`: exact diameter (prohibitively slow at N=400)

### Baseline Comparisons

Run from `TetriX/latency/brute_force.py`. Baselines:
- **Random K-ring:** RAPID default
- **Chord:** DHT-style structured routing
- **Perigee:** latency-aware but non-RL heuristic
- **Nearest neighbor:** greedy by edge weight
- **Genetic Algorithm:** evolutionary search baseline

---

## Evaluation Protocol

`TetriX/latency/test.py` — called from training loop every 100 epochs:

```python
test_diameter, test_graph = test(args, agent=agent, log_file=log_file, num_tests_startnode=1)
```

- Runs the greedy policy (epsilon=0) on the test graph
- Reports weighted diameter of the resulting overlay
- If `test_diameter < best_test_diameter`, saves checkpoint and topology

---

## FABRIC Experiment Workflow

1. Launch 20 VMs per site across 20 FABRIC sites
2. Run `fabric.sh` on each to bootstrap
3. Set `experiment.yaml`: `numNodes: 400`, `baseIP: <seed-node-ip>`
4. `mvn clean install` on all nodes
5. Launch RAPID processes; wait for membership to stabilize
6. DGRO runs automatically via `MembershipService.createDGRO()`
7. Collect logs; latency data written to `system_monitor.csv`

---

## Log Files

| File | Contents |
|------|----------|
| `system_monitor.csv` | Per-node CPU/memory/network metrics during experiment |
| `firewall-cmd.out` | Firewall configuration output (Chameleon) |
| `TetriX/sc_test/FABRIC_400.json` | Raw pairwise latency measurements |
| NERSC `output` files | Per-epoch training logs |

---

## Reproducibility Notes

- All Python experiments use `torch.manual_seed`, `random.seed`, `np.random.seed` from `args.seed`
- Default seed is `1`
- Training is not perfectly deterministic due to GPU non-determinism; expect ±2% variance in final diameter across runs
- The `G_400_FABRIC.pkl` graph is fixed — re-running with the same seed should produce very similar results
