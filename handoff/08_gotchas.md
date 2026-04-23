# 08 — Known Issues & Tribal Knowledge

These are the things that will waste your day if you don't know them up front.

---

## Java Build

### Maven SSL Failure

**Symptom:** `mvn clean install` fails with `PKIX path building failed` or similar SSL certificate error.

**Fix:** Always build with `MAVEN_OPTS`:

```bash
export MAVEN_OPTS="-Dmaven.resolver.transport=wagon \
  -Dmaven.wagon.http.ssl.insecure=true \
  -Dmaven.wagon.http.ssl.allowall=true \
  -Dmaven.wagon.http.ssl.ignore.validity.dates=true"
mvn clean install
```

This is already in `install.sh` but not exported persistently — you must re-export in each new shell session unless you add it to `~/.bashrc`.

### JDK Version

RAPID is built against JDK 9. Using JDK 11+ may trigger warnings or errors around modules. Stick with JDK 9.0.4 as installed by `install.sh`.

---

## Firewall / Networking

### Chameleon Nodes

Gossip port (default 1234) is blocked by default on Chameleon. Open it with:

```bash
sudo iptables -I INPUT -p tcp --dport 1234 -j ACCEPT
```

This is not persisted across reboots. Add to `/etc/rc.local` or use `firewall-cmd` (installed by `install_bash.sh`) for persistence.

### FABRIC Nodes

FABRIC uses a different security model (security groups in the portal). You don't need iptables — configure port access through the FABRIC portal UI before launching experiments.

---

## Python / GraphEnv

### Missing `G_400_FABRIC.pkl`

`GraphEnv.__init__` calls `load_graph()` which opens `../sc_test/G_400_FABRIC.pkl`. If you run from a directory other than `TetriX/latency/`, this relative path breaks. Always run training scripts from `TetriX/latency/`.

### Reset Uses Hardcoded Path

`env.reset()` also hardcodes `../sc_test/G_400_FABRIC.pkl`. If you want to use a different graph, you must edit `env.py` — there is no command-line argument for this.

### Diameter Approximation Is Not Exact

`num_sources=2` means diameter is approximated as the max over BFS from nodes 0 and 1. This is fast but can underestimate the true diameter. If you see suspiciously good test numbers, increase `num_sources` to verify.

### `sum(mask) == 0` Edge Case

In `env.step()`, if all nodes are masked (no valid edge targets), the mask resets to all-ones. This is a workaround for a corner case in degree-constrained graph construction — the resulting graph may have degree violations at the very end of an episode. The workaround is correct in practice but worth being aware of.

---

## Training

### Model Saved to pscratch, Not Local

`train.py` saves checkpoints to `/pscratch/sd/s/swu264/SWARM/model/`. On a non-NERSC machine this path doesn't exist and `os.makedirs` will fail. Change `args.experiment_name` in `init()` before running locally:

```python
# Line 59 in train.py — change this:
args.experiment_name = '/pscratch/sd/s/swu264/SWARM/model/' + str(current_time)
# To something like:
args.experiment_name = './runs/' + str(current_time)
```

### `pkl` Not Imported in train.py

`train.py` line 167 uses `pkl.dump(...)` but `pickle` is not imported in that file — `pkl` comes from `brute_force.py` via `from brute_force import *`. If you refactor imports, add `import pickle as pkl` explicitly.

### Epsilon Decay Schedule

Epsilon decays as `max(1 - epoch/2000, 0.05)`. `epoch` is the number of learning steps (not episodes). At batch size 64 and N=400, this means exploration is essentially over by ~2000 learning steps — relatively fast. If you increase N significantly, consider scaling the decay denominator.

---

## RAPID Integration

### DGRO Restarts on Every Membership Change

`MembershipService.java` calls `createDGRO()` on every join/leave/failure event. For large clusters with frequent churn, this can cause DGRO to restart continuously. There is no debouncing — consider adding a cooldown timer if you see DGRO thrashing.

### `stopDGRO()` Must Be Called Before Shutdown

If you kill a RAPID process without graceful shutdown, the `dgroExecutor` thread pool may not be cleaned up. This is usually fine for experiments but can leave zombie threads in long-running deployments.

---

## FABRIC Testbed

### Node IP Addresses Change Between Allocations

FABRIC assigns new IPs each time you create a new slice. Update `experiment.yaml` and any hardcoded IPs after each allocation. The `baseIP` field in `experiment.yaml` is the seed node IP.

### pscratch Purge Policy

Files in `/pscratch/` at NERSC are deleted after approximately 30 days without access. Any model you care about must be copied to `$HOME` or `$CFS` promptly after training.

---

## test.sh Batch Delay

`test.sh` has a `sleep` between launching the seed and launching subsequent processes. If you remove this sleep, processes may try to join before the seed is ready and fail with connection refused. Keep the delay.
