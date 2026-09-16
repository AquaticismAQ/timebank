# TimeBank Deployment Tiers: Decision Guide for School Project Leads

> **Budget-planning guide, not a supplier quotation.** Prepared 2026-09-01 for an institution deciding how to begin operating TimeBank. Amounts are monthly and annual planning bands in CNY, before tax, promotions, education discounts, domain/ICP/WAF fees, implementation labour, and any data-migration project. Obtain a dated Alibaba Cloud quotation for the selected region before approval.

> **Required deployment inputs — obtain these before selecting a tier.**
>
> 1. **Domain and DNS change:** the school network administrator must choose the approved school subdomain and update its DNS record to point to the new server or load-balancer public address. Use a school-owned address such as `tb.example.com` or `timebank.example.com`; the actual institutional domain must be configured by the school and must not be recorded in this repository. TLS certificate issuance and HTTPS testing follow the DNS change.
> 2. **Student roster import sample:** the school must provide a *fictional or fully anonymised* roster sample in the agreed import format so that the import process can be verified. A real student roster is highly sensitive personal information. It must be prepared and imported by an authorised school staff member, not by development or maintenance personnel. The school must validate the resulting accounts and report any mapping issue using non-identifying examples.
> 3. **Historic paper-ledger samples:** the school must provide redacted sample pages from its existing paper account ledger, together with an explanation of each column and exception. These samples are needed to complete and verify the historical-ledger import so that it matches the school’s established records. Do not send originals or files containing unnecessary student identifiers to development personnel.
> 4. **E-mail forwarding service, if notifications are required:** the school must procure and configure an approved transactional e-mail or SMTP-relay service if it wants system e-mail notifications. The provider must support the school domain, sender authentication, delivery monitoring, and bounce handling. This is a separate service from the application server; it is included only as a small allowance in the tier estimates and requires a final provider quote.

## 1. The decision in one page

TimeBank handles student personal data, service records, audit history, and evidence files. The system should therefore be selected by the acceptable length of an outage and amount of data that could need restoration—not only by the lowest cloud bill.

| Tier | Suitable use | Estimated monthly cost | Estimated annual cost | Plain-language recommendation |
| --- | --- | ---: | ---: | --- |
| **1. Low-cost pilot** | One school’s limited trial, UAT, demonstrations, or an early controlled rollout | **¥900–¥1,100** | **¥10,800–¥13,200** | Choose only when a service interruption of a day or more is acceptable and the school has a named technical person who can restore the server. |
| **2. Managed-database standard** | A school’s normal first production deployment | **¥1,500–¥1,800** | **¥18,000–¥21,600** | **Recommended starting point for most schools.** It keeps one application server but moves MySQL to Alibaba Cloud RDS, reducing database-operation risk. |
| **3. Resilient disaster-recovery deployment** | A system relied upon daily by multiple grades, with a low tolerance for interruption or data loss | **¥3,800–¥4,800** | **¥45,600–¥57,600** | Choose when continuity is a school-management requirement and funding is available for operational rehearsals as well as cloud services. |

The ranges deliberately include an allowance for variable RDS pricing, evidence-download traffic, backup growth, and selected-region differences. They do **not** make an unavailable service acceptable simply because its subscription is paid: backup restoration and failover must be tested.

## 2. What every tier includes

All three tiers include the web application, HTTPS access, private database access, evidence storage in a private object-storage bucket, routine email notices, monitoring of basic capacity, and backups. Evidence files should be stored in object storage and accessed through time-limited authorised links; they should not be retained on the application server’s local disk.

The estimates assume a single school with up to roughly 3,000 students, 300 staff accounts, and a design target of 300 peak concurrent users. That target still requires a production-shaped load test; this guide is a funding decision, not proof of capacity.

## 3. Tier 1 — Low-cost pilot

### Proposed setup

- One 4 vCPU / 16 GiB server with a 60 GB system disk, running the application, Nginx, and self-managed MySQL.
- Private object storage for evidence files, plus backup copies and transaction-email delivery.
- A single public entry point; no standby application server and no database standby.

### Budget composition

| Main cost area | Monthly planning allowance | Notes |
| --- | ---: | --- |
| 4 vCPU / 16 GiB ECS, 60 GB SSD, and 5 Mbps fixed bandwidth | ¥773 | ¥600 compute + approximately ¥48 SSD + ¥125 bandwidth. |
| Object storage, backups, e-mail, and small traffic allowance | ¥100–¥300 | Includes an initial modest evidence and download allowance; actual evidence volume can raise this. |
| **Total** | **¥900–¥1,100** | **¥10,800–¥13,200 per year** (rounded). |

### What users experience

In ordinary use, the portal can provide the same core functions as the other tiers: students submit records and evidence, and staff review and audit them. It is the simplest system to purchase and operate initially.

Because this tier now uses four CPU cores, it is a controlled pilot configuration, not a 300-concurrent-user capacity claim. Keep the initial pilot small, schedule bulk imports and reports outside teaching hours, and expand the server after measured load testing rather than relying on this budget figure.

### Strengths, limits, and risks

| Strengths | Limits and risks |
| --- | --- |
| Lowest recurring cost and fastest to understand. | One server failure, disk failure, failed upgrade, or MySQL problem can stop the entire system. |
| Suitable for learning the real usage pattern before a wider launch. | Recovery depends on a usable backup and a person able to restore it; downtime may be many hours or longer. |
| 60 GB local disk keeps the server cost low. | The 60 GB disk is **not** evidence capacity. Local evidence, logs, database growth, and temporary files can fill it quickly. |

**Decision boundary:** use this tier only as a time-bounded pilot. Do not silently let a successful pilot become a school-wide, business-critical service without reassessing the risk and upgrading.

## 4. Tier 2 — Managed-database standard (recommended)

### Proposed setup

- One application server, initially 4–8 vCPU / 16 GiB with approximately 100 GB SSD.
- Alibaba Cloud RDS for MySQL in a single zone, initially 4 vCPU / 16 GiB with 200 GB database storage. The managed database replaces self-managed MySQL operations; the high-availability RDS edition and multi-zone application deployment belong in Tier 3.
- Private object storage for evidence, managed database backup/PITR capacity, email service, and basic monitoring.

### Budget composition

| Main cost area | Monthly planning allowance | Notes |
| --- | ---: | --- |
| Application ECS, 100 GB SSD, and 5 Mbps fixed bandwidth | ¥825 | ¥600 compute + approximately ¥100 SSD + ¥125 bandwidth. |
| RDS MySQL Basic Edition, 4 vCPU / 16 GiB, 200 GB storage | ¥550 | ¥480 instance + ¥70 high-performance cloud-disk storage. |
| Object storage, backup overage, e-mail, and basic monitoring allowance | ¥125–¥425 | Evidence growth and backup-retention policy are the main variables. |
| **Total** | **¥1,500–¥1,800** | **¥18,000–¥21,600 per year** (rounded). |

### What users experience

Normal users should see no functional difference from Tier 1. The practical improvement is that the provider operates the database software and backup facilities, reducing the school’s routine MySQL maintenance burden.

### Strengths, limits, and risks

| Strengths | Limits and risks |
| --- | --- |
| Lower operational burden for the database; clearer provider support boundary. | The application and single-zone database remain single points of failure, so a server- or zone-level fault can still cause an outage. |
| Better basis for backup retention and point-in-time recovery. | Managed RDS does not replace restore testing, access control, or careful application/database changes. |
| A reasonable balance between cost and operational simplicity for one school. | It has no automatic cross-zone database failover; select Tier 3 when continuity requirements are stronger. |

## 5. Tier 3 — Resilient disaster-recovery deployment

### Proposed setup

- Two application servers placed across separate availability zones, behind a load balancer. Either server can continue serving the portal if the other fails.
- Alibaba Cloud RDS for MySQL High-availability Edition across zones, with automated failover and point-in-time recovery.
- Private object storage for evidence, lifecycle management, cross-zone or cross-region backup copies where permitted by the data-residency policy, and monitored backup verification.
- Centralised monitoring and alerts, documented incident contacts, and scheduled restore/failover rehearsals.

### Budget composition

| Main cost area | Monthly planning allowance | Notes |
| --- | ---: | --- |
| Two application ECS hosts, disks, 5 Mbps bandwidth, and load balancer allowance | ¥1,625–¥1,825 | Two 4 vCPU / 16 GiB hosts, each with 100 GB SSD; the range includes a load-balancer allowance. |
| RDS MySQL High-availability Edition, 4 vCPU / 16 GiB, 200 GB storage, and backup/PITR allowance | ¥1,560–¥1,860 | ¥1,360 instance + ¥100 storage, plus backup-retention allowance. |
| Evidence storage, protected copy, e-mail, monitoring, and recovery-verification allowance | ¥615–¥1,115 | Includes 700 GB primary evidence capacity, a protected copy, download/replication allowance, and recurring checks. |
| **Total** | **¥3,800–¥4,800** | **¥45,600–¥57,600 per year** (rounded). |

### What users experience

Most failures should cause no interruption or a much shorter interruption than in Tiers 1–2. During a severe infrastructure fault, the school may see a short maintenance notice while services switch or recover, rather than waiting for a new server to be rebuilt from backup.

### Strengths, limits, and risks

| Strengths | Limits and risks |
| --- | --- |
| Removes the single application-server failure point and gives the database a managed standby. | It costs substantially more every month and requires ongoing technical ownership. |
| Strongest practical protection for daily school operations and evidence continuity. | It is not an absolute guarantee: a defective release, incorrect deletion, compromised account, or regional event can still affect service. |
| Regular recovery drills turn backups from an assumption into evidence. | Cross-region disaster recovery must follow the school’s data-residency and personal-information approval requirements; it must not be enabled casually. |

## 6. Risks that the budget cannot purchase away

| Risk | Required school action |
| --- | --- |
| Evidence volume exceeds the allowance | Review object-storage use each month; use approved lifecycle and retention rules rather than deleting files ad hoc. |
| An account is misused or a staff member makes an incorrect decision | Maintain role permissions, audit-log review, and the established non-overwritable review process. |
| A backup exists but cannot be restored | Test restoration regularly into an isolated temporary environment and record the outcome. |
| A software update causes an outage | Use a staging environment and a rollback plan before production changes. |
| Disaster recovery is assumed but never practiced | Run a documented failover drill at least annually for Tier 3 and after material architecture changes. |

## 7. Approval checklist and recommended path

For most schools, approve **Tier 2** for the first normal production year, with a written review after one semester of measured usage. Start with Tier 1 only when the scope is explicitly a controlled pilot and the leadership accepts its outage and recovery risk. Approve Tier 3 when the school depends on the service for routine daily operations and has decided that a prolonged outage or avoidable data-recovery event is unacceptable.

Before signing a contract, the project lead should confirm:

1. The selected cloud region, data-residency position, and required institutional/legal approvals.
2. A current supplier quote for compute, RDS edition, storage, bandwidth, and backup retention.
3. The named technical owner and escalation contact for incidents.
4. The acceptable maximum outage and acceptable maximum data-recovery gap, expressed in hours.
5. The budget and calendar for load testing, backup restoration, and, for Tier 3, failover rehearsal.

## 8. Pricing basis and relationship to the detailed estimate

The amounts above were recalculated from Alibaba Cloud’s currently published public planning prices for the China regions, using month-to-month figures and no promotional price: ECS `ecs.c5.2xlarge` (8 vCPU / 16 GiB) ¥1,092/month; ECS `ecs.g5.xlarge` (4 vCPU / 16 GiB) ¥600/month; 5 Mbps fixed bandwidth ¥125/month; RDS MySQL 4 vCPU / 16 GiB Basic Edition ¥480/month; and the corresponding High-availability Edition ¥1,360/month. SSD and RDS storage are charged separately. OSS Standard LRS is ¥0.12/GB-month; the geographically redundant OSS example is ¥0.15/GB-month, while public download traffic is ¥0.50/GB in the published example.

These are public reference prices, not a locked quotation: selected region, CPU family, disk type, purchase term, RDS edition, backup retention, and traffic all change the bill. Sources: [ECS price list](https://ecs-buy.aliyun.com/price), [ECS public-bandwidth billing](https://help.aliyun.com/zh/ecs/public-bandwidth/), [RDS MySQL public configuration price table](https://developer.aliyun.com/article/1751050), [OSS storage fees](https://help.aliyun.com/zh/oss/storage-fees), and [OSS billing examples](https://help.aliyun.com/zh/oss/billing-examples).

This document is a simplified funding decision aid. The detailed component assumptions, capacity assumptions, and overseas comparison remain in [Deployment Cost Estimate](DeploymentCostEstimate.md). In particular, see its §2 for capacity assumptions and §5 for required go-live costs that are intentionally excluded from the headline totals. Its Alibaba Cloud component amounts predate this recalculation and should not be used in place of this guide’s figures.
