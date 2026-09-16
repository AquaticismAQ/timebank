# Test Server Capacity Estimate

> Engineering estimate, not a measured capacity guarantee. Prepared on 2026-08-26 for a dedicated test server with 8 CPU cores, 8 GB RAM, and 60 GB storage. The required 300-concurrent-user load test has not yet been run.

## 1. Decision Summary

| Capacity dimension | Recommended planning value | Conditional upper target |
|---|---:|---:|
| Registered users for functional/UAT testing | 5,000 students + 500 staff | Feasible when evidence files are sparse or synthetic |
| Active users with one year of representative evidence stored locally | 1,000–1,500 students + 100–150 staff | About 1,875 student-years only under the design-average file size and strict disk control |
| Simultaneously online users | 100–200 | 250–300 after tuning and load-test verification |
| Sustained request rate | 40–60 requests/second | Not a substitute for workload-based verification |
| Short burst request rate | 100–150 requests/second | Unverified; depends on endpoint mix and database contention |
| Concurrent evidence uploads | 10–20 | Higher values require measured disk, network, and JVM headroom |

The server is suitable for functional testing, integration testing, demonstrations, and a controlled pilot. Eight CPU cores are sufficient for the expected application workload. Eight GB of RAM is workable only when the JVM and MySQL share memory carefully. The principal hard limit is the 60 GB disk: it cannot retain full-school, multi-year evidence at the architecture's design volume.

## 2. Scope and Assumptions

This estimate assumes:

- the host is dedicated to TimeBank during performance testing;
- Nginx, the Spring Boot backend, MySQL, logs, and locally stored evidence all share the same host;
- the React frontend is served as static assets and consumes negligible steady-state CPU;
- normal traffic consists of login, paginated lists, detail views, application/review workflows, account queries, and unread-notification polling;
- imports, exports, reports, file uploads, and background jobs are heavier than normal browsing;
- HTTPS termination and network bandwidth are adequate; and
- no unrelated build, backup, or analysis workload runs during a capacity test.

The formal architecture target is 5,000 students, 500 staff, 300 peak concurrent users, 60 sustained requests/second, and 200 requests/second in bursts. See [Backend Design §3.1](../backend/Design.md#31-design-target-per-deployment--per-school). Those are system design targets, not evidence that this particular all-in-one host has achieved them.

## 3. Architecture Factors That Determine Capacity

### 3.1 Application and database execution

Spring Boot uses virtual threads, which reduce the cost of waiting threads but do not create database capacity. Requests that need MySQL still compete for HikariCP connections. The current default pool size is 5, intentionally conservative for the older shared test host. On this 8 GB host, a pool of 15 is a reasonable starting point; 20 should be used only after measuring MySQL memory, lock contention, and response latency.

The application is mostly transactional CRUD and paginated query work. This is favorable for CPU capacity, but dashboards, broad filters, exports, and poorly indexed queries can consume disproportionate database time.

### 3.2 Database size and access pattern

The backend design estimates 10–20 GB of relational data at steady state. That fits on disk only if evidence files are stored elsewhere and logs/binlogs are controlled. It will not all fit in RAM, so index quality and the MySQL buffer-pool working set are more important than raw account count.

Registered accounts themselves are inexpensive. Activity history, service records, audit logs, notification rows, and account transactions determine database growth and query pressure.

### 3.3 Background work

The background job executor currently has 2 workers and a queue of 50. Keeping two workers is appropriate on this host because imports and report generation can otherwise compete with interactive traffic. A large import or export should not be used to judge normal browsing capacity unless that interference is explicitly part of the scenario.

### 3.4 Evidence storage

Evidence is currently stored through the local-storage adapter, with individual uploads allowed up to 20 MB. The design estimate is 200,000 evidence files per year and 550–700 GB over approximately seven years for a 5,000-student school.

Derived annual planning values:

```text
550–700 GB / 7 years = approximately 79–100 GB per school-year
79–100 GB / 5,000 students = approximately 16–20 MB per student-year
```

The averages hide burst risk. A class uploading several photos at once can stress network, temporary disk space, JVM request handling, and storage I/O even when the annual average appears safe.

## 4. Capacity Estimate

### 4.1 Registered accounts and business rows

| Scenario | Estimated supported population | Reasoning |
|---|---:|---|
| Sparse functional/UAT data | 5,000 students + 500 staff | Account and ordinary relational rows are not the primary limit |
| Representative one-year activity with local evidence | 1,000–1,500 students + 100–150 staff | Leaves practical space for database growth, logs, temporary files, and safety margin |
| Multi-year full-school history | Not recommended on 60 GB | Evidence alone exceeds the disk budget by a wide margin |

The 5,500-account design population can therefore be seeded and exercised on this host, but it should not be confused with the ability to retain a production-shaped evidence archive.

### 4.2 Simultaneous access and request rate

| Load level | Assessment |
|---|---|
| 100 simultaneous online users | Comfortable target for ordinary mixed navigation |
| 200 simultaneous online users | Recommended planning ceiling before formal load verification |
| 250–300 simultaneous online users | Plausible tuned test target, but must be proven with the production-shaped scenario |
| More than 300 simultaneous online users | Outside the current per-school design target and not supported by this estimate |

“Online” is not the same as “executing a request at the same instant.” For example, 300 users polling unread counts every 30 seconds generate about 10 requests/second before page navigation and mutations are added.

The expected sustainable range is 40–60 requests/second for a realistic mixed workload. Short bursts of 100–150 requests/second may be absorbed, but this is not a guarantee. The formal 200-requests/second burst target should not be claimed on this host until it is measured.

Evidence submission is a separate constraint. Limit planned simultaneous uploads to roughly 10–20 until representative file sizes are tested. Five hundred students may submit within 30 minutes, but they should not all upload at the same second.

### 4.3 Local-storage capacity

A reasonable 60 GB allocation is:

| Use | Planning allocation |
|---|---:|
| OS, packages, JDK, application releases | 8–12 GB |
| MySQL data, indexes, binlogs, and temporary space | 6–10 GB initially |
| Application/Nginx logs, exports, upload temporary files, operating headroom | 4–6 GB |
| Mandatory free-space reserve | 10–12 GB |
| Remaining evidence budget | Approximately 20–30 GB |

At the architecture's average of 16–20 MB per student-year, 20–30 GB represents approximately 1,000–1,875 student-years. The recommended range is narrower—1,000–1,500 active students for one representative year—because averages do not cover large photos, retries, exports, binlog spikes, or operational mistakes.

If actual evidence averages 1 MB per file and each student produces about 40 files per year, usage becomes about 40 MB per student-year. The same evidence budget then supports only about 500–750 student-years.

### 4.4 Effect of external object storage

Moving evidence to external object storage removes the dominant disk-growth constraint. The 5,000-student plus 500-staff population then becomes reasonable for functional and performance testing on this host, although 300 concurrent users still require load verification.

External object storage does not remove:

- MySQL and JVM memory competition;
- database connection-pool limits;
- upload bandwidth and request-size pressure;
- background-job interference; or
- the need for evidence lifecycle, authorization, and audit controls.

## 5. Recommended Configuration for the 8 GB Host

| Component | Starting configuration | Notes |
|---|---|---|
| Spring Boot JVM | `-Xms1g -Xmx2g` | Verify native memory, thread stacks, and file-upload behavior |
| HikariCP | `TIMEBANK_DB_POOL_SIZE=15` | Consider 20 only after measurement |
| MySQL InnoDB buffer pool | About 3 GB | 3.5–4 GB only if OS and JVM headroom remain healthy |
| Background executor | 2 workers, queue 50 | Keep current setting initially |
| OS/Nginx/native/page cache reserve | About 2 GB | Avoid swap during sustained tests |
| Free disk alert | Before 20% free | Stop evidence growth before 10% free |

Operational controls:

- rotate and cap application, Nginx, and MySQL logs;
- monitor MySQL binlog retention and temporary-disk usage;
- do not run Maven builds, frontend builds, backups, or large package upgrades during performance tests;
- schedule bulk imports and large reports outside interactive-load tests;
- monitor evidence size by school and by month; and
- make evidence cleanup an authorized, auditable lifecycle operation rather than an ad hoc file deletion.

## 6. Required Verification Before Raising the Limits

Run a production-shaped load test before advertising 250–300 concurrent users:

1. Seed approximately 5,000 students, 500 staff, realistic projects, applications, service records, account transactions, notifications, and audit logs.
2. Run mixed user journeys at 100, 200, and 300 concurrent users.
3. Separately test the required scenario of 500 students submitting within 30 minutes, using representative evidence sizes.
4. Run a large import concurrently in a separate interference test.
5. Record p50/p95/p99 latency, error rate, CPU, JVM heap and GC, swap, Hikari active/pending connections, MySQL buffer-pool and slow-query metrics, disk latency/free space, and background queue depth.

Acceptance should retain the backend design targets: p95 under 300 ms for list/detail endpoints and under 1 second for dashboards, with no sustained connection-pool queue, swap thrashing, background-queue overflow, or disk exhaustion.

## 7. Final Recommendation

| Operating mode | Population | Simultaneous access | Evidence strategy |
|---|---:|---:|---|
| Full-account UAT | 5,000 students + 500 staff | Plan 100–200; test 300 | Sparse or synthetic local evidence |
| Realistic one-year local pilot | 1,000–1,500 students + 100–150 staff | 100–200 | Representative evidence stored locally |
| Full-school performance test | 5,000 students + 500 staff | Target 300 after tuning | External object storage strongly recommended |

In short: CPU is adequate, RAM is workable but tightly shared, and storage is the limiting resource. Use this server for a controlled pilot or full-account UAT. Add object storage—or materially more disk—before treating it as a production-shaped full-school evidence deployment.
