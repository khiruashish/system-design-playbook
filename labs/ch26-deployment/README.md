# Ch 26 labs: Deployment & Infrastructure

Java 21. L1–L3 run real HTTP instances behind a real load balancer. **L4 uses real Linux cgroups (v1)** — the kernel mechanism
containers are built on — so it needs root and a cgroup-v1 host (or adapt paths for cgroup v2).

| Level | Run | Output |
|---|---|---|
| L1 deployment strategies (recreate/rolling/blue-green/canary) | `java -Dsun.net.httpserver.nodelay=true DeployL1.java` | `deploy_out.txt` |
| L2 health probes (readiness/liveness/startup) | `java -Dsun.net.httpserver.nodelay=true HealthL2.java` | `health_out.txt` |
| L3 autoscaling control loop | `java AutoscaleL3.java` | `autoscale_out.txt` |
| L4 container limits via real cgroups | `./cg.sh <name> <memMB> <cpus> -- java -XX:MaxRAMPercentage=75 Hog.java [mem|cpu <ops>]` | `cgroup_out.txt` |

L4 examples (as root):
```bash
./cg.sh probe 256 2 -- java -XX:MaxRAMPercentage=75 Hog.java        # heap follows the 256MB limit
./cg.sh oom  256 2 -- java -Xmx400m Hog.java mem                    # exit 137: kernel OOM-kill
./cg.sh cpu  1024 0.5 -- java Hog.java cpu 800000000                # CPU throttling at 0.5 cores
```
The Kubernetes YAML in the chapter is VERIFY (no cluster here); the cgroups in L4 are the real mechanism K8s limits use.
Counts and timings vary run to run; the shapes (blast radius, probe effect, thrash count, 137 vs 3) are the point.
