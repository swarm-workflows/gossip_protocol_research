# Project Handoff — Resilience Research (SWARM / DGRO)

**Outgoing researcher:** Shixun Wu (swu264@ucr.edu)  
**Date prepared:** April 2026  
**Advisor:** (confirm with your advisor)  
**Collaborators:** ANL, RENCI, LBNL, USC/ISI (SWARM Team)

---

## What This Project Is

Two tightly coupled research threads:

1. **DGRO** (Diameter-Guided Ring Optimization) — a reinforcement-learning (DQN) approach to optimize the gossip-overlay topology of a distributed membership protocol (RAPID), minimizing communication diameter under a fixed degree budget. This produced a **conference paper submitted to SC**.

2. **SWARM** — DOE FOA-0002902, led by Ewa Deelman (USC/ISI). The goal is Swarm Intelligence (SI++) for resilient scientific workflows across DOE Integrated Research Infrastructure (IRI). DGRO is the networking layer contribution from UCR.

---

## Where to Start

Read the handoff documents in order:

| # | File | What it covers |
|---|------|----------------|
| 1 | [handoff/01_overview.md](handoff/01_overview.md) | Project overview, goals, current status |
| 2 | [handoff/02_codebase_map.md](handoff/02_codebase_map.md) | Directory tour — what every folder contains |
| 3 | [handoff/03_setup_guide.md](handoff/03_setup_guide.md) | Environment setup, testbeds, build instructions |
| 4 | [handoff/04_experimental_log.md](handoff/04_experimental_log.md) | Experimental details, datasets, artifact locations |
| 5 | [handoff/05_contacts.md](handoff/05_contacts.md) | Team contacts and their roles |
| 6 | [handoff/06_future_work.md](handoff/06_future_work.md) | Planned next steps (agent on supercomputer, etc.) |
| 7 | [handoff/07_publications.md](handoff/07_publications.md) | Paper status and SWARM deliverables |
| 8 | [handoff/08_gotchas.md](handoff/08_gotchas.md) | Known issues and tribal knowledge — read before touching anything |
| 9 | [handoff/09_reading_list.md](handoff/09_reading_list.md) | Papers to read before extending the work |

---

## Repo Layout (one line each)

```
gossip_protocol_research/
├── rapid/          # Java RAPID membership protocol (the runtime)
├── TetriX/         # Python RL training, simulation, evaluation
│   ├── latency/    # DGRO DQN agent — primary ML code
│   ├── tetrix/     # SWARM agent/gossip simulation
│   ├── sc_test/    # Pre-collected FABRIC graphs and saved topologies
│   ├── rl/         # Earlier GCN/L2O experiments (historical)
│   └── ...         # Other experiment subdirectories by venue
├── experiment.yaml # Cluster config (numNodes, port, gossipType)
├── install.sh      # Java + Maven setup
├── install_bash.sh # Shell + conda setup
├── test.sh         # Launch 100 local RAPID processes
└── fabric.sh       # Bootstrap script for FABRIC nodes
```

---

## Quickest Smoke Test

```bash
# 1. Build Java runtime
bash install.sh
# 2. Run 100-node local simulation
bash test.sh
```

See [handoff/03_setup_guide.md](handoff/03_setup_guide.md) for full details.
