#!/bin/bash
# cg.sh — run a command inside a REAL cgroup v1 memory+cpu limit (what a container runtime does under the hood).
# usage: cg.sh <name> <memMB> <cpus> -- <command...>
set -e
name="$1"; memMB="$2"; cpus="$3"; shift 3; [ "$1" = "--" ] && shift
M=/sys/fs/cgroup/memory/$name; C=/sys/fs/cgroup/cpu/$name
mkdir -p "$M" "$C"
echo $((memMB*1024*1024)) > "$M/memory.limit_in_bytes"
echo $((memMB*1024*1024)) > "$M/memory.memsw.limit_in_bytes" 2>/dev/null || true   # no swap escape
echo 0 > "$M/memory.swappiness" 2>/dev/null || true
echo 100000 > "$C/cpu.cfs_period_us"
echo $(awk "BEGIN{printf \"%d\", $cpus*100000}") > "$C/cpu.cfs_quota_us"
# put THIS shell (and its exec'd child) into the cgroups, then exec the command
echo $$ > "$M/cgroup.procs"; echo $$ > "$C/cgroup.procs"
exec "$@"
