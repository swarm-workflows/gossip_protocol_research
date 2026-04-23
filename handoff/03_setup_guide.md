# 03 — Setup Guide

## Prerequisites

- Linux or macOS (scripts use bash/zsh)
- Access to FABRIC or Chameleon testbed account
- NERSC account (for pscratch model storage)
- Conda installed

---

## Step 1 — Python Environment

Run `install_bash.sh` on a fresh node, or replicate manually:

```bash
conda create -n gossip python=3.12
conda activate gossip
pip install torch torch-geometric networkx gym wandb numpy
```

The conda env name `gossip` is assumed throughout the Python code.

---

## Step 2 — Java / Maven Build

```bash
bash install.sh
```

This script:
1. Downloads OpenJDK 9.0.4 to `$HOME/software/jdk-9.0.4`
2. Downloads Apache Maven 3.9.6 to `$HOME/software/apache-maven-3.9.6`
3. Exports `JAVA_HOME` and `PATH` (also writes to `~/.zshrc`)
4. Sets `MAVEN_OPTS` with SSL workaround flags (see [08_gotchas.md](08_gotchas.md))
5. Runs `mvn clean install`

If you already have a JDK and Maven installed, you can skip the download steps and just run `mvn clean install` from the repo root, but **you must set `MAVEN_OPTS`** or the build will fail on certificate errors.

### Manual MAVEN_OPTS (if needed)

```bash
export MAVEN_OPTS="-Dmaven.resolver.transport=wagon \
  -Dmaven.wagon.http.ssl.insecure=true \
  -Dmaven.wagon.http.ssl.allowall=true \
  -Dmaven.wagon.http.ssl.ignore.validity.dates=true"
mvn clean install
```

---

## Step 3 — Local Smoke Test (100 nodes)

```bash
bash test.sh
```

This launches one RAPID seed node at `127.0.0.1:1234`, then 50 additional processes on ports `1235–1284`. The processes join the cluster and begin gossiping.

Watch logs to confirm membership stabilizes before proceeding to experiments.

---

## Cluster Configuration

Edit [experiment.yaml](../experiment.yaml) before running:

```yaml
baseIP: 127.0.0.7   # Change to actual seed IP for remote clusters
port: 1234
numNodes: 100       # Must match the number of processes you launch
gossipType: 0       # 0 = standard gossip, other values = experimental modes
```

---

## FABRIC Testbed Setup

FABRIC is a nationwide research testbed. The project uses 20 sites, 20 VMs per site = 400 total processes.

**Relevant sites used in experiments:**  
MASS, TACC, MICH, UCSD, HAWI, CERN (and others; see `FABRIC_400.json` headers).

### Bootstrap a FABRIC Node

```bash
bash fabric.sh
```

This script installs zsh, miniconda, and sets up the conda environment on an Ubuntu FABRIC VM.

### Firewall Rule (Chameleon)

On Chameleon nodes you may need:

```bash
sudo iptables -I INPUT -p tcp --dport 1234 -j ACCEPT
```

(Replace `1234` with your configured port.) FABRIC uses a different firewall model — check FABRIC portal for security group settings.

---

## NERSC / Perlmutter Access

Trained models are saved to:

```
/pscratch/sd/s/swu264/SWARM/model/<timestamp>/
```

Each timestamped directory contains:
- `config.json` — the args used for that training run
- `output` — training log (reward, loss, test diameter per epoch)
- `*.pth` — PyTorch model checkpoint (saved by `agent.save()`)

**Note:** `pscratch` is a scratch filesystem — files are purged periodically (typically after 30 days). Copy important checkpoints to `$HOME` or `$CFS` on NERSC.

To load a checkpoint for inference:

```python
agent.load('/pscratch/sd/s/swu264/SWARM/model/<timestamp>/<name>.pth')
```

The `load_path` argument in `train.py` / `init()` feeds into `agent.load()`.

---

## Running DGRO Training

```bash
cd TetriX/latency
conda activate gossip
python train.py --N 400 --K 3 --bs 64 --feature_dim 4 --lr 5e-4 --num_sources 2
```

Key arguments:

| Arg | Default | Meaning |
|-----|---------|---------|
| `--N` | 400 | Number of nodes |
| `--K` | 3 | Max out-degree per node |
| `--bs` | 64 | Replay buffer sample batch size |
| `--feature_dim` | 4 | Graph embedding dimension |
| `--lr` | 5e-4 | Adam learning rate |
| `--num_sources` | 2 | Sources for diameter approximation (higher = more accurate, slower) |
| `--reward_mode` | diameter | Reward shaping mode |
| `--seed` | 1 | Random seed |
| `--if_wandb` | False | Enable Weights & Biases logging |
| `--load_path` | None | Resume from checkpoint |

Training runs for up to 400,000 episodes. Checkpoints are saved automatically when test diameter improves.
