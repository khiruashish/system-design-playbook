# Labs

Every chapter from 01 to 62 solves one problem at four levels (L1–L4). Chapters 10–62 have a folder here with the
runnable programs, their recorded outputs (the `*_out.txt` files quoted verbatim in the chapter) and a README with the
exact command for each lab. The [Lab Catalogue](../docs/99-appendix/lab-catalogue.md) lists all of them.

## Run and verify them: `run-labs.py`

`labs.json` is the manifest: for every lab, the README's command, what it needs, the recorded output it should reproduce
and the result lines to check. `run-labs.py` runs labs exactly as their READMEs say and checks that each one

1. exits with status 0 within its timeout, and
2. still prints the result lines the chapter quotes (`expect` in the manifest). Those lines were taken from the recorded
   output and confirmed identical on independent re-runs: seeded simulations, counts and verdicts. Timings and other
   machine-dependent numbers are not checked.

```bash
python3 labs/run-labs.py --list                    # every lab, its tier and how many result lines are checked
python3 labs/run-labs.py --tier jdk                # labs that need only JDK 21
python3 labs/run-labs.py --only ch45,ch62          # one or more chapters

docker compose -f labs/docker-compose.yml up -d    # PostgreSQL 16 on :5581 (db shop, user claude), Redis 7 on :6390
python3 labs/run-labs.py --tier postgres,redis     # the database labs
docker compose -f labs/docker-compose.yml down -v

JAVA25_HOME=/path/to/jdk-25 python3 labs/run-labs.py --tier jdk25   # Ch 19 and Ch 22 labs that need JDK 25
```

JDBC drivers and the Kafka client jar are downloaded once into `labs/.cache/`. CI (`.github/workflows/labs.yml`) runs the
JDK labs in four parallel shards, the JDK 25 labs, and the database labs against service containers, on every change under
`labs/`, weekly, and on demand.

### Tiers

| Tier | Needs | How to provide it |
|---|---|---|
| `jdk` | JDK 21 as `java` | any JDK 21 |
| `jdk25` | JDK 25 | set `JAVA25_HOME` |
| `postgres` | PostgreSQL 16 on :5581, db `shop`, user `claude`, trust auth | `docker-compose.yml` |
| `redis` | Redis 7 on :6390 (some labs `FLUSHALL` it: use a scratch instance) | `docker-compose.yml` |
| manual | a chapter-specific setup script or tool: PostgreSQL clusters on other ports with special settings (Ch 13, 15, 20, 21), etcd, NATS, SeaweedFS, Meilisearch, OpenBao, cgroups | follow that chapter's README |

`--list` shows each manual lab with the reason.

### Adding or changing a lab

1. Write the program and its README row (command and output file) as the other chapters do.
2. Add an entry to `labs.json` (copy a neighbour; `needs` lists the tier, `out` the recorded output file).
3. Record its checks: run `python3 labs/run-labs.py --record --only chNN` twice. The second run keeps only lines that were
   identical both times, so timing-dependent lines drop out.
4. Commit the program, its `*_out.txt`, the README and `labs.json`.

## Appendix tools

`appendix-tools/` regenerates the Q&A bank, question index, glossary and lab catalogue from the chapters; see its README.
