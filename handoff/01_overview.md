# 01 — Project Overview & Current Progress

## The Core Problem

In large-scale distributed systems (supercomputers, DOE IRI), nodes must exchange state continuously via **gossip protocols**. The topology of who talks to whom determines:
- **Convergence latency** — how many rounds until all nodes know a piece of information
- **Fault tolerance** — does the network stay connected if nodes fail?
- **Bandwidth cost** — what is the total data volume per gossip round?

The standard approach (random K-ring) is simple but ignores real latency. DGRO replaces random wiring with a learned, latency-aware topology that minimizes diameter subject to a degree budget K.

---

## System Architecture

```
┌─────────────────────────────────────────┐
│  RAPID Java runtime (rapid/)            │
│  - Stable membership (join/leave/fail)  │
│  - Gossip broadcaster                   │
│  - DGRO integration (MembershipService) │
└───────────────┬─────────────────────────┘
                │ triggers on membership change
┌───────────────▼─────────────────────────┐
│  DGRO optimizer (TetriX/latency/)       │
│  - DQN selects edges greedily           │
│  - Graph embedding (message passing)    │
│  - Trained offline, loaded at runtime   │
└───────────────┬─────────────────────────┘
                │ produces overlay graph
┌───────────────▼─────────────────────────┐
│  SWARM agent layer (TetriX/tetrix/)     │
│  - Scientific workflow DAG execution    │
│  - Gossip-based state sharing           │
│  - Resilience to node failures          │
└─────────────────────────────────────────┘
```

---

## What Has Been Done

### DGRO (SC paper — primary contribution)

- [x] Formulated topology optimization as a Markov Decision Process
- [x] Designed graph embedding via T-step message passing (theta_1–theta_11 layers); see [TetriX/latency/DQNAgent_graph.py](../TetriX/latency/DQNAgent_graph.py)
- [x] Reward function: `prev_diameter - cur_diameter - edge_weight` (balances diameter reduction vs. latency cost of the chosen edge)
- [x] Diameter approximation: single-source BFS from `num_sources` nodes instead of APSP; 450× speedup at N=400
- [x] Trained and evaluated on real FABRIC latency graphs (`TetriX/sc_test/G_400_FABRIC.pkl`)
- [x] Parallel DGRO with partition-level rewards; see [TetriX/latency/parallel/train_parallel_improved.py](../TetriX/latency/parallel/train_parallel_improved.py)
- [x] Integrated into RAPID Java runtime via `MembershipService.createDGRO()` / `stopDGRO()`
- [x] Baselines implemented: Chord, Perigee, RAPID K-ring, Genetic Algorithm, nearest-neighbor

### SWARM / TetriX

- [x] Gossip simulation environment (`TetriX/tetrix/envs/gossip.py`) — multiprocess, queue-based
- [x] Agent DAG executor (`TetriX/tetrix/agents/agent.py`) — dependency tracking via `predecessor_count`
- [x] FABRIC testbed experiments at 400-node scale (20 sites × 20 processes)
- [x] Latency data collection pipeline (`TetriX/sc_test/FABRIC_400.json`)

---

## Current Status (April 2026)

| Item | Status |
|------|--------|
| DGRO SC paper | Submitted; awaiting reviews |
| Parallel DGRO | Implemented, preliminary results only |
| RAPID integration | Working; needs re-test after any RAPID version update |
| SWARM agent on supercomputer | Not started — primary future work |
| Intra-supercomputer topology | Concept only |
| Chameleon evaluation | Partial; FABRIC is the primary testbed |

---

## Key Numbers to Know

| Metric | Value |
|--------|-------|
| Default network size | N=400, K=3 |
| Training episodes | 400,000 max |
| Replay buffer | 1,000,000 transitions |
| Epsilon decay | `max(1 - epoch/2000, 0.05)` |
| FABRIC sites | 20 (MASS, TACC, MICH, UCSD, HAWI, CERN, …) |
| Best test diameter (FABRIC 400) | See `sc_test/best_test_graph.pkl` metadata |
| Model save location | `/pscratch/sd/s/swu264/SWARM/model/` (NERSC) |
