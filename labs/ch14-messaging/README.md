# Ch 14 labs — messaging & event streaming

## NATS JetStream, 3 nodes (real)

Requirements: `nats-server` 2.10+ and `nats` CLI 0.2+ (GitHub releases of nats-io/nats-server and nats-io/natscli),
unpacked into one folder; set `NATS_BIN` (default `/opt/nats`). `python3` for parsing JSON.

```bash
mkdir -p /tmp/natslab && cp setup-nats.sh exp-nats.sh /tmp/natslab/ && cd /tmp/natslab
./setup-nats.sh && ./exp-nats.sh      # ~90 s; kills and restarts nodes on purpose
```

Stop: `pkill -x nats-server`

## Redis 7 Streams (real)

```bash
./exp-redis-streams.sh                 # starts its own redis-server on :6390 and shuts it down at the end
```

## Java programs

```bash
java QueueVsLogL1.java
java -cp kafka-clients-3.9.0.jar:slf4j-api-1.7.36.jar KafkaClientsL2.java   # real Kafka client library, no broker
java ReplicationL3.java                                                    # ~35 s
java ConsumerL4.java
```

`kafka-clients` 3.9.0 (+ `slf4j-api`) can come from any Kafka 3.9 distribution's `libs/` folder or Maven Central.
