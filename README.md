# L7 Adaptive Defense

MSc thesis project at Swansea University.

The idea: normal firewalls/API gateways only see traffic from the outside, so they miss slow attacks that stay under the rate threshold, and they can't tell users apart when many people share the same IP (office NAT, mobile carrier, etc.) — blocking the IP blocks everyone. This project fixes both by also looking inside the microservices (failed logins, 5xx errors, latency) and scoring per-*identity* instead of per-IP, then reacting step by step (rate limit → tarpit → drop) instead of just blocking everything at once.

Scoring happens off to the side via Kafka, not on the request path, so it never adds latency to a normal request.

## Status

Done. Full closed loop built and evaluated: 40 k6 runs across 4 scenarios × 2 conditions × 5 repeats, all valid. See `analysis/out/summary.md` for results.

## What's in here

- `gateway` - Spring Cloud Gateway. Resolves identity, checks cached decisions, enforces mitigation, publishes signals to Kafka, consumes decisions back
- `auth-service` - login, issues JWT tokens
- `orders-service` - the thing being protected
- `risk-engine` - consumes signals from Kafka, scores identities over a sliding window, publishes decisions
- `k6` - the 4 evaluation scenarios (S1-S4)
- `scripts` - PowerShell orchestration for running the full matrix
- `analysis` - Python, turns raw run output into the metrics/tables used in the report
- `runs` - the 40 measured runs (raw data + computed metrics)

## Stack

Java 21, Spring Boot 4 (WebFlux for the gateway, MVC + virtual threads for the rest), Spring Cloud Gateway, Kafka (KRaft), PostgreSQL, Caffeine, Bucket4j, k6, Docker Compose.

## Running it

```bash
docker compose up -d
```

Gateway is the only port exposed (8080), everything else is internal-only.

```bash
curl -X POST localhost:8080/auth/register -H "Content-Type: application/json" -d '{"username":"alice","password":"CorrectHorseBattery1"}'
curl -X POST localhost:8080/auth/login    -H "Content-Type: application/json" -d '{"username":"alice","password":"CorrectHorseBattery1"}'
curl localhost:8080/items -H "Authorization: Bearer <token>"
```

## Reproducing the evaluation

Needs [k6](https://k6.io).

```powershell
.\scripts\run_matrix.ps1                     # full 40-run matrix, ~4.5h
.\scripts\run_matrix.ps1 -Repeats 1 -Smoke   # 8 quick runs to sanity-check the pipeline
python analysis\aggregate_matrix.py
```

Baseline condition = `docker compose stop risk-engine` (static rate limiter only). Adaptive = full loop. Results go to `analysis/out/`.

## Tests

```bash
mvn -pl risk-engine test
```

Unit tests are deliberately scoped to the scoring/decision logic in risk-engine — that's the part where a silent bug is dangerous. The gateway filters and the Kafka pipeline are exercised end-to-end by every one of the 40 runs instead, so a container test suite there would mostly duplicate that.

Note: a plain `mvn test` also runs the Spring context tests, and the gateway's needs a live Kafka to start (`docker compose up -d kafka` first) — otherwise it fails on that alone, not on anything actually broken.

Credentials in `docker-compose.yml` are dev values, committed on purpose so the whole thing runs with one command. All evaluation traffic is synthetic.
