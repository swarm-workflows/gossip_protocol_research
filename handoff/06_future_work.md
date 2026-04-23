# 06 — Future Work

## Priority 1 — SWARM Agent System on Supercomputer

**What:** Deploy the TetriX agent/gossip system on a real supercomputer (Perlmutter at NERSC is the most accessible given existing pscratch access).

**Current state:** `TetriX/tetrix/` has a simulation using Python `multiprocessing`. This works on a single machine but does not scale to the MPI/Slurm job model used by supercomputers.

**What needs to happen:**
1. Replace `multiprocessing.Process` + `Queue` with MPI (mpi4py) or a Slurm job array
2. Each "agent" becomes an MPI rank or a Slurm task
3. Gossip communication moves from in-memory queues to MPI send/recv or a lightweight message broker (e.g., Redis Pub/Sub over the high-speed fabric)
4. The DAG executor in `agent.py` needs to handle task failures — currently `finish_task()` assumes success

**Relevant files:**
- [TetriX/tetrix/envs/gossip.py](../TetriX/tetrix/envs/gossip.py)
- [TetriX/tetrix/agents/agent.py](../TetriX/tetrix/agents/agent.py)

**Suggested first step:** Run `TetriX/tetrix/` on Perlmutter as a single-node job first, then scale with MPI.

---

## Priority 2 — Intra-Supercomputer Topology Optimization

**What:** Apply DGRO inside a single supercomputer (e.g., within a Perlmutter allocation) to optimize the gossip overlay among MPI ranks.

**The opportunity:** Supercomputer interconnects (Dragonfly, Fat-tree) have heterogeneous hop counts and congestion patterns. DGRO could learn topology that respects the physical network hierarchy.

**What needs to happen:**
1. Collect intra-supercomputer latency data (analogous to `FABRIC_400.json` but from Perlmutter)
2. Construct a graph `G_Perlmutter.pkl` in the same format as `G_400_FABRIC.pkl`
3. Retrain DGRO on this graph (or fine-tune from FABRIC checkpoint)
4. Evaluate whether DGRO improves gossip convergence vs. random wiring on the same hardware

**Challenge:** Supercomputer schedulers give dynamic allocations — the set of nodes changes per job. DGRO needs to handle dynamic N (currently N is fixed at 400). This likely requires re-training or a meta-learning approach.

---

## Priority 3 — Parallel DGRO at Scale

**What:** The current parallel implementation (`parallel/train_parallel_improved.py`) uses M=4 partitions. Scale this to larger M and validate that partition-level rewards compose correctly.

**Known issue:** The alpha annealing schedule (0.5 → 0.8) was tuned heuristically. A principled approach (e.g., curriculum learning) would strengthen the SC paper and future work.

---

## Priority 4 — Generalization Across Topologies

**What:** DGRO is currently trained and tested on the same graph (`G_400_FABRIC.pkl`). Real deployment requires generalization to unseen graphs.

**What needs to happen:**
1. Collect latency data from multiple testbeds (FABRIC + Chameleon + Perlmutter)
2. Train on a distribution of graphs instead of a single graph
3. Evaluate zero-shot transfer to a new testbed

This is a research contribution in itself and could be a follow-on paper.

---

## Priority 5 — Fault Tolerance Integration

**What:** RAPID detects node failures and calls `createDGRO()` again to re-optimize. But the current DGRO re-runs from scratch — it doesn't warm-start from the previous topology.

**What needs to happen:**
- Add a warm-start mode: given the current (damaged) graph, apply DGRO incrementally
- This reduces re-convergence time after failures, which is the core resilience metric for SWARM

---

## Longer-Term Ideas (from SWARM proposal)

- **Cross-facility workflow resilience:** A DAG task that fails at TACC can be re-submitted to NERSC — the gossip layer needs to propagate this decision in sub-second time
- **Predictive rerouting:** Use historical failure patterns to pre-position backup routes
- **Integration with workflow managers:** Connect TetriX agents to Parsl, Pegasus, or RADICAL-Cybertools (all SWARM partners)
