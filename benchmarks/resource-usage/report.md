# Resource usage SQL benchmark

Status: passed

1,000,000 bookings, 100 resources, 900,000 active and 100,000 cancelled.

PostgreSQL image: postgres:17-alpine. 16 connections, 4 client workers. Four measured runs of 30 s in raw/view/view/raw order, each preceded by 8 s warmup.

The query returns 31 UTC days for one resource, including zero days, resource name, counts, booked seconds and utilization. The separate freshness lookup and HTTP API are excluded. Both queries use the same database with all migration indexes enabled. The materialized view is refreshed before measurements; no writes or refresh run during load.

raw: pooled p50 8.267 ms, pooled p95 61.143 ms, throughput 1029.4 transactions/s.
view: pooled p50 2.422 ms, pooled p95 49.762 ms, throughput 2997.0 transactions/s.

Raw/view pooled p95 ratio: 1.229x. View p95 change: 18.61%.

Initial concurrent refresh: 2.729 s. Unchanged concurrent refresh: 3.029 s. These wall-clock costs include Docker exec and psql startup.

Run 1 (raw): p50 8.316 ms, p95 61.209 ms, 1023.4 transactions/s, 30,713 completed transactions.
Run 2 (view): p50 2.423 ms, p95 49.887 ms, 2981.1 transactions/s, 89,428 completed transactions.
Run 3 (view): p50 2.421 ms, p95 49.601 ms, 3012.8 transactions/s, 90,363 completed transactions.
Run 4 (raw): p50 8.217 ms, p95 61.062 ms, 1035.5 transactions/s, 31,082 completed transactions.

Percentiles use the nearest-rank method on every successful transaction from raw pgbench logs. Pooled percentiles weight each transaction equally across the two runs. Throughput is total transactions divided by the sum of measured durations inferred from each pgbench TPS, excluding initial connections. Queries, plans, raw logs, migrations, index definitions and machine context are included in the artifact. The client and database share the same constrained container; these are synthetic SQL measurements, not API latency or a production capacity claim.
