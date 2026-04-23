# 02 — Codebase Map

## Top-Level Files

| File | Purpose |
|------|---------|
| [README.md](../README.md) | Basic setup and launch instructions |
| [experiment.yaml](../experiment.yaml) | Cluster config: `baseIP`, `port`, `numNodes`, `gossipType` |
| [install.sh](../install.sh) | Downloads JDK 9.0.4 + Maven 3.9.6, builds Java project |
| [install_bash.sh](../install_bash.sh) | Installs zsh, conda, creates `gossip` conda env (Python 3.12) |
| [test.sh](../test.sh) | Launches 100-node local RAPID cluster for smoke testing |
| [fabric.sh](../fabric.sh) | Bootstrap script to run on each FABRIC node |
| [test_initial.sh](../test_initial.sh) | Alternative launch script |
| [pom.xml](../pom.xml) | Maven project descriptor (Java dependencies) |
| [monitor.py](../monitor.py) | Live system resource monitor |
| [plot.py](../plot.py) | Result plotting utilities |

---

## `rapid/` — Java Runtime

The RAPID membership protocol, extended with gossip broadcasting and DGRO.

```
rapid/src/main/java/com/vrg/rapid/
├── MembershipService.java   # Core — where DGRO is integrated
├── ...                      # Standard RAPID files (do not need deep knowledge)
```

**Key integration points in `MembershipService.java`:**
- Line ~182: `createDGRO()` called on initialization
- Line ~514: `createDGRO()` called again on membership change (node join/leave/fail)
- Line ~471: `stopDGRO()` tears down the optimizer gracefully
- `dgroJobs` / `dgroExecutor`: scheduled thread pool managing DGRO runs
- `latencyProbeJobs`: periodic latency measurement via `LatencyMessage` proto

Build command: `mvn clean install` (with `MAVEN_OPTS` — see [handoff/08_gotchas.md](08_gotchas.md)).

---

## `TetriX/` — Python ML / Simulation

### `TetriX/latency/` — Primary ML Code (DGRO)

| File | Purpose |
|------|---------|
| [train.py](../TetriX/latency/train.py) | Main training loop; args parsing; replay buffer; calls `test()` periodically |
| [env.py](../TetriX/latency/env.py) | `GraphEnv` gym environment; reward shaping; diameter approximation; degree mask |
| [DQNAgent_graph.py](../TetriX/latency/DQNAgent_graph.py) | `DQNNetwork` with message-passing embedding; `DQNAgent` with epsilon-greedy act + learn |
| [test.py](../TetriX/latency/test.py) | Evaluation loop — runs greedy policy on test graphs |
| [brute_force.py](../TetriX/latency/brute_force.py) | Baseline implementations (nearest-neighbor, Chord, etc.) |
| `parallel/train_parallel_improved.py` | Parallel DGRO with M=4 partitions and alpha annealing |

### `TetriX/sc_test/` — Data and Artifacts

| File | Purpose |
|------|---------|
| `G_400_FABRIC.pkl` | 400-node weighted graph from FABRIC latency measurements — **the primary training/test graph** |
| `G_100_FABRIC.pkl` | 100-node version (for quick experiments) |
| `FABRIC_400.json` | Raw latency measurements from FABRIC testbed |
| `best_test_graph.pkl` | Best topology found by DGRO (overwritten each training run) |
| `best_test_graph_*.pkl` | Snapshots saved at specific milestones |

> **Warning:** `G_400_FABRIC.pkl` is loaded at `env.py` init. If the file is missing, every `GraphEnv` instantiation will crash. Keep a backup.

### `TetriX/tetrix/` — SWARM Agent Simulation

| File | Purpose |
|------|---------|
| `envs/gossip.py` | Gossip environment using `multiprocessing.Process` + `Queue`; nodes exchange state dicts; random sleep intervals simulate network delay |
| `agents/agent.py` | `Agent` class: `get_task()` and `finish_task()` with DAG dependency tracking via `predecessor_count` |

### `TetriX/rl/` — Earlier Experiments (Historical)

| File | Purpose |
|------|---------|
| `model.py` | GCN/GAT `ActorNetwork` — superseded by `DQNAgent_graph.py` |
| `l2o.py` | L2O `MatrixGenerator` optimizing A⁴ trace — early research direction, not used in SC paper |
| `readme.md` | Problem formulation notes for the A⁴ maximization approach |

### Other `TetriX/` Subdirectories

| Directory | Purpose |
|-----------|---------|
| `ipdps_test/` | Experiments for IPDPS submission (earlier venue) |
| `hpdc_test/` | Experiments for HPDC submission |
| `compare_random_K_ring/` | Head-to-head comparison vs. random K-ring baseline |
| `ring_selection/` | Ring topology selection experiments |
| `marl/` | Multi-agent RL experiments (exploratory) |
| `claude3/` | LLM-assisted code generation experiments |
| `test_cugraph/` | GPU-accelerated graph operations (cuGraph) experiments |
| `ComputeDistribution/` | Compute distribution analysis utilities |
| `utils/` | Shared utility functions |

---

## Data Flow Summary

```
FABRIC testbed
     │  latency measurements
     ▼
TetriX/sc_test/FABRIC_400.json
     │  processed by data_processing.py
     ▼
TetriX/sc_test/G_400_FABRIC.pkl   ←── loaded by GraphEnv at every reset()
     │
     ▼
TetriX/latency/train.py  ──trains──►  DQNAgent_graph.py
     │  saves checkpoint
     ▼
/pscratch/sd/s/swu264/SWARM/model/<timestamp>/   (NERSC pscratch)
     │  also saves best graph locally
     ▼
TetriX/sc_test/best_test_graph.pkl
     │  loaded by RAPID at runtime
     ▼
rapid/  (Java) — DGRO-optimized overlay active
```
