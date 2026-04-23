# 09 — Reading List

Read these in the order listed. The first three are essential before touching the code.

---

## Essential (Read First)

### 1. RAPID — Stable and Consistent Membership at Scale
**Why:** RAPID is the runtime this project extends. You need to understand stable membership, alert generation, and the gossip broadcaster before touching `MembershipService.java`.  
**Authors:** Lalith Suresh et al. (VMware Research)  
**Find it:** Search "RAPID stable membership protocol" — published at NSDI/ATC.

### 2. Perigee: Efficient Peer-to-Peer Network Design for Blockchains
**Why:** Perigee is one of the primary baselines in the SC paper. Understanding its approach (latency-aware peer selection without RL) clarifies what DGRO improves upon.  
**Find it:** Search "Perigee blockchain peer network" — PODC 2021.

---

## Reinforcement Learning Background

### 3. Playing Atari with Deep Reinforcement Learning (DQN)
**Why:** DGRO uses DQN. If you haven't implemented DQN before, read the original paper.  
**Authors:** Mnih et al., DeepMind  
**Find it:** arXiv 1312.5602

### 4. Learning Combinatorial Optimization Algorithms over Graphs
**Why:** The graph embedding approach in `DQNAgent_graph.py` (message-passing, theta layers) is directly inspired by this work (S2V-DQN).  
**Authors:** Dai et al. (CMU / Georgia Tech), NeurIPS 2017  
**Find it:** arXiv 1704.01665

---

## Graph Theory / Network Optimization

### 5. Chord: A Scalable Peer-to-peer Lookup Service for Internet Applications
**Why:** Chord is a baseline and a widely-cited structured overlay. Understanding it helps contextualize why DGRO's unstructured approach can outperform it on latency.  
**Authors:** Stoica et al., SIGCOMM 2001

### 6. Efficient Graph Diameter Approximation
**Why:** The diameter approximation trick (BFS from a small number of sources) is a known technique. Understanding its theoretical guarantees (and when it fails) is important for interpreting DGRO results.  
**Find it:** Search "graph diameter approximation BFS" — results in theoretical CS.

---

## Testbed / Infrastructure

### 7. FABRIC: A National Cyberinfrastructure for Research on Networks
**Why:** You will run experiments on FABRIC. This paper describes the testbed architecture, site locations, and capabilities.  
**Find it:** Search "FABRIC testbed RENCI" — SIGCOMM CCR or similar.

---

## SWARM Project Context

### 8. The SWARM Proposal (DOE FOA-0002902)
**Why:** This is the funding document. It defines what UCR committed to deliver and on what timeline. Table 1 is the most important part.  
**Access:** Ask your advisor — it is not publicly available.

### 9. Pegasus Workflow Management System
**Why:** Pegasus is a SWARM partner tool. The TetriX agent system is meant to eventually integrate with or complement Pegasus-managed workflows. Ewa Deelman's group develops Pegasus.  
**Find it:** Search "Pegasus workflow management" — extensive documentation at pegasus.isi.edu.

---

## Optional / Background

### 10. Graph Neural Networks: A Review of Methods and Applications
**Why:** The earlier `TetriX/rl/model.py` used GCN/GAT. If you want to understand why message-passing embedding was chosen over GNN layers, this survey provides context.  
**Authors:** Zhou et al., AI Open 2020

### 11. Genetic Algorithms for Network Topology Optimization
**Why:** GA is one of the baselines. Understanding its encoding and fitness function helps when reproducing `brute_force.py` results.  
**Find it:** Any standard GA / evolutionary computation survey.
