#!/bin/bash
# exp-ratelimit.sh — REAL per-tenant rate limiting + usage metering on REAL Redis, with tiered SaaS plans. Each tenant's
# plan sets its token-bucket capacity + refill rate. We fire an identical burst of 30 requests at three tenants on
# different plans and count ALLOWED vs THROTTLED (429), then read the per-tenant usage meter that billing would bill on.
set -e
R="redis-cli -p 6390"
$R FLUSHALL >/dev/null
SHA=$($R -x SCRIPT LOAD < /tmp/tenantlab/token_bucket.lua)

# plan definitions: name -> "capacity refill_per_sec"
declare -A PLAN=( ["free"]="5 0.5" ["pro"]="20 5" ["enterprise"]="200 50" )
declare -A TENANT_PLAN=( ["acme"]="free" ["globex"]="pro" ["initech"]="enterprise" )

echo "Per-tenant rate limiting on real Redis (atomic token bucket). Each tenant fires 30 requests in a tight burst."
echo "Plans:  free = capacity 5 (refill 0.5/s),  pro = capacity 20 (refill 5/s),  enterprise = capacity 200 (refill 50/s)"
echo ""
printf "   %-9s %-11s %8s %10s %10s\n" "tenant" "plan" "sent" "allowed" "throttled"

NOW=$($R TIME | head -1)000   # seconds -> ms (coarse; the burst is near-instant so refill ≈ 0)
for tenant in acme globex initech; do
  plan=${TENANT_PLAN[$tenant]}
  read cap rate <<< "${PLAN[$plan]}"
  allowed=0; throttled=0
  for i in $(seq 1 30); do
    ok=$($R EVALSHA "$SHA" 1 "rl:$tenant" "$cap" "$rate" "$NOW" 1)
    if [ "$ok" = "1" ]; then
      allowed=$((allowed+1))
      $R INCR "usage:$tenant:2026-09-26" >/dev/null    # meter only billable (served) requests
    else
      throttled=$((throttled+1))
    fi
  done
  printf "   %-9s %-11s %8d %10d %10d\n" "$tenant" "$plan" 30 "$allowed" "$throttled"
done

echo ""
echo "== Usage meter (what billing reads for the period) =="
for tenant in acme globex initech; do
  used=$($R GET "usage:$tenant:2026-09-26")
  printf "   %-9s served requests today: %s\n" "$tenant" "$used"
done

echo ""
echo "== Refill: wait 4s and let the free tenant (acme) try again =="
sleep 4
NOW2=$($R TIME | head -1)000
refilled=0
for i in $(seq 1 10); do
  ok=$($R EVALSHA "$SHA" 1 "rl:acme" 5 0.5 "$NOW2" 1)
  [ "$ok" = "1" ] && refilled=$((refilled+1))
done
echo "   acme (free) after 4s idle: $refilled of 10 allowed — the bucket refilled ~0.5 token/s * 4s ≈ 2 tokens. Steady state, not a reset."
