# Design: New Checkout Service

## Background
Checkout is slow and we want to rebuild it with microservices.

## Proposed Design
We will build a fast, highly scalable checkout service using Kafka and Redis.
It will be highly available and secure. Latency will be low and it will support real-time inventory.

## Alternatives
We could keep the old one, but it is slow.

## Rollout
Deploy it.
