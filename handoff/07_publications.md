<!-- # 07 — Publications & Deliverables

## SC Paper (Primary Contribution)

**Title:** DGRO — Diameter-Guided Ring Optimization for Gossip Protocol Topology  
**Venue:** Supercomputing (SC) — submission in progress as of April 2026  
**Status:** Submitted; awaiting reviews  
**Authors:** Shixun Wu, [your advisor], [SWARM co-authors as agreed]

### What the Paper Claims

1. Formulating gossip overlay design as an MDP with a latency-aware reward
2. Graph embedding via T-step message passing for compact state representation
3. Diameter approximation (single-source BFS) replacing APSP — 450× speedup at N=400
4. FABRIC evaluation at 400-node scale, outperforming Chord, Perigee, K-ring, GA baselines
5. Parallel DGRO with partition-level rewards

### Paper Artifacts

- Training code: `TetriX/latency/train.py`, `DQNAgent_graph.py`, `env.py`
- Evaluation code: `TetriX/latency/test.py`, `brute_force.py`
- Data: `TetriX/sc_test/G_400_FABRIC.pkl`, `FABRIC_400.json`
- Best topology: `TetriX/sc_test/best_test_graph.pkl`
- Model checkpoints: `/pscratch/sd/s/swu264/SWARM/model/` (NERSC)

**If the paper is accepted:** Make sure all artifact paths in the paper match what's actually in the repo. The pscratch paths will expire — move final checkpoints to a persistent location (Zenodo, GitHub release, or NERSC `$CFS`) before submission of final version.

---

## SWARM Project Deliverables (DOE FOA-0002902)

The SWARM proposal (Table 1) defines milestones by project year. UCR's networking contribution maps to:

| Deliverable | Description | UCR Role |
|-------------|-------------|----------|
| D1 — Gossip layer design | Gossip protocol integration with RAPID | Primary |
| D2 — Topology optimizer | DGRO implementation and evaluation | Primary |
| D3 — FABRIC integration | 400-node experiments on FABRIC testbed | Primary |
| D4 — Agent system | TetriX gossip simulation | Supporting |
| D5 — Cross-facility demo | End-to-end workflow across IRI sites | Supporting |

Check with your advisor for exact deliverable numbers and deadlines from the current proposal period — the table above is a paraphrase, not verbatim.

---

## Related Prior Work (from the group)

Earlier venue attempts that informed the SC submission:
- `TetriX/ipdps_test/` — IPDPS submission experiments
- `TetriX/hpdc_test/` — HPDC submission experiments

The SC paper supersedes these; do not re-submit the same results elsewhere without checking with your advisor.

---

## Reporting to DOE / SWARM

SWARM has periodic progress reports to DOE. UCR typically contributes:
- Updated diameter/latency benchmark numbers
- FABRIC experiment logs
- Any new baselines or algorithmic improvements

Coordinate timing with Ewa Deelman's group — they compile the joint report. -->
