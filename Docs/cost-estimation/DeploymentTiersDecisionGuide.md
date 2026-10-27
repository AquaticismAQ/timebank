# TimeBank Deployment Tiers: Decision Guide for School Project Leads

> **Budget-planning guide, not a supplier quotation.** Prepared 2026-09-01 for an institution deciding how to begin operating TimeBank. Amounts are monthly and annual planning bands in CNY, before tax, promotions, education discounts, domain/ICP/WAF fees, implementation labour, and any data-migration project. Obtain a dated Alibaba Cloud quotation for the selected region before approval.

> **Required deployment inputs — obtain these before selecting a tier.**
>
> 1. **Domain and DNS change:** the school network administrator must choose the approved school subdomain and update its DNS record to point to the new server or load-balancer public address. Use a school-owned address such as `tb.example.com`; the actual institutional domain must be configured by the school and must not be recorded in this repository. TLS certificate issuance and HTTPS testing follow the DNS change.
> 2. **Student roster import sample:** the school must provide a *fictional or fully anonymised* roster sample in the agreed import format so that the import process can be verified. A real student roster is highly sensitive personal information. It must be prepared and imported by an authorised school staff member, not by development or maintenance personnel. The school must validate the resulting accounts and report any mapping issue using non-identifying examples.
> 3. **Historic paper-ledger samples:** the school must provide redacted sample pages from its existing paper account ledger, together with an explanation of each column and exception. These samples are needed to complete and verify the historical-ledger import so that it matches the school’s established records. Do not send originals or files containing unnecessary student identifiers to development personnel.
> 4. **E-mail forwarding service, if notifications are required:** the school must procure and configure an approved transactional e-mail or SMTP-relay service if it wants system e-mail notifications. The provider must support the school domain, sender authentication, delivery monitoring, and bounce handling. This is a separate service from the application server; it is included only as a small allowance in the tier estimates and requires a final provider quote.

## 1. The decision in one page

TimeBank handles student personal data, service records, audit history, and evidence files. The system should therefore be selected by the acceptable length of an outage and amount of data that could need restoration—not only by the lowest cloud bill.

| Tier | Core deployment | Suitable use | Estimated monthly cost | Estimated annual cost | Plain-language recommendation |
| --- | --- | --- | ---: | ---: | --- |
| **1. Low-cost pilot** | 1 lowest-cost ECS + **RDS MySQL High Availability (2 cores / 4 GB / 50 GB, TDE)** + OSS + free default KMS | One school’s limited trial, UAT, demonstrations, or an early controlled rollout | **¥421.11** (annual-price equivalent) | **¥5,053.32** | Choose only when an application-server interruption is acceptable and the school has a named technical person who can restore the server. |
| **2. Managed-database standard** | 1 application ECS + single-zone RDS + OSS | A school’s normal first production deployment | **¥1,500–¥1,800** | **¥18,000–¥21,600** | **Recommended starting point for most schools.** It keeps one application server but moves MySQL to Alibaba Cloud RDS, reducing database-operation risk. |
| **3. Resilient disaster-recovery deployment** | 2 application ECS hosts + load balancer + RDS High Availability + protected OSS copy | A system relied upon daily by multiple grades, with a low tolerance for interruption or data loss | **¥3,800–¥4,800** | **¥45,600–¥57,600** | Choose when continuity is a school-management requirement and funding is available for operational rehearsals as well as cloud services. |

The Tier 2 and 3 ranges deliberately include an allowance for variable RDS pricing, evidence-download traffic, backup growth, and selected-region differences. Tier 1 is calculated from the lowest annual-purchase prices listed in this guide; it excludes public bandwidth, domain/ICP/WAF, email, backup overage, and OSS request/download charges that are usage-based or procured separately. Paying for a subscription does **not** make an unavailable service acceptable: backup restoration and failover must be tested.

## 2. What every tier includes

All three tiers include the web application, HTTPS access, private database access, evidence storage in a private object-storage bucket, routine email notices, monitoring of basic capacity, and backups. Evidence files should be stored in object storage and accessed through time-limited authorised links; they should not be retained on the application server’s local disk.

The estimates assume a single school with up to roughly 3,000 students, 300 staff accounts, and a design target of 300 peak concurrent users. That target still requires a production-shaped load test; this guide is a funding decision, not proof of capacity.

## 3. Tier 1 — Low-cost pilot

### Proposed setup

- One `ecs.e-c1m1.large` ECS instance (2 vCPU / 2 GiB), running the application and Nginx. This is the lowest-priced ECS shown in the supplied pricing capture and is suitable only for a small pilot.
- One RDS for MySQL High-availability-series instance (2 cores / 4 GB, 50 GB) for the business database; MySQL is not self-managed on the ECS host. This is the smallest captured configuration that meets the TDE requirement and includes a managed standby node.
- 100 GB OSS Standard locally redundant storage for evidence files. The database and object storage use server-side encryption provided by KMS default keys.
- KMS uses a free default service/master key, not a paid Software Key Management Instance; backup copies and transactional email are procured as needed.
- A single public entry point and no standby application server. The RDS managed standby protects only the database; it does not remove the application-layer interruption risk of a single ECS host.

### Budget composition

| Main cost area | Monthly planning allowance | Notes |
| --- | ---: | --- |
| `ecs.e-c1m1.large` ECS (2 vCPU / 2 GiB) | ¥19.86 | Promotional monthly price shown in the supplied capture; ¥238.32/year if the same rate applies for 12 months. |
| RDS for MySQL High-availability series (2 cores / 4 GB, 50 GB) | ¥393.00 (annual-price equivalent) | ¥4,716.00 annual price shown in the supplied capture. The Basic series is ¥1,726.80/year but does not support the TDE required by this security design. The monthly figure is for comparison, not a monthly quotation. |
| OSS Standard LRS (100 GB) | ¥8.25 (annual-price equivalent) | ¥99.00 annual price shown in the supplied capture. The monthly figure is for comparison, not a monthly quotation. |
| KMS default service/master key | ¥0 | Cloud-product server-side encryption can use a free default key; this excludes a paid Software Key Management Instance. |
| **Total** | **¥421.11** (annual-price equivalent) | **¥5,053.32/year** (ECS ¥238.32 + RDS ¥4,716.00 + OSS ¥99.00). |

### What users experience

In ordinary use, the portal can provide the same core functions as the other tiers: students submit records and evidence, and staff review and audit them. It is the simplest system to purchase and operate initially.

Because this tier uses a 2 vCPU / 2 GiB ECS host and the smallest captured RDS size that supports TDE, it is a controlled pilot configuration, not a 300-concurrent-user capacity claim. Keep the initial pilot very small, schedule bulk imports and reports outside teaching hours, and expand the service after measured load testing rather than relying on this budget figure.

### Strengths, limits, and risks

| Strengths | Limits and risks |
| --- | --- |
| Lowest recurring cost while meeting the TDE, KMS, RDS, and OSS infrastructure prerequisites, and fast to understand. | A single application-server failure, disk failure, or failed upgrade can still stop the portal; the RDS standby cannot take over the application. |
| Suitable for learning the real usage pattern before a wider launch. | Recovery depends on a usable backup and a person able to restore it; downtime may be many hours or longer. |
| Managed RDS, private OSS, and a KMS default key meet this system's required database, evidence-storage, and server-side-encryption infrastructure prerequisites. | The 100 GB OSS allocation is only initial evidence capacity; download traffic, requests, backups, and excess storage are billed separately. Do not mistake the annual package prices for the full operating cost. |

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

Tier 1 uses the annual/promotional prices in the supplied captures: `ecs.e-c1m1.large` ECS (2 vCPU / 2 GiB) at a promotional ¥19.86/month, RDS for MySQL High-availability series (2 cores / 4 GB, 50 GB) at ¥4,716.00/year, and OSS Standard LRS (100 GB) at ¥99.00/year. The captured RDS Basic-series price is ¥1,726.80/year, but the Basic series does not support the MySQL TDE required by this system's security design, so it cannot be the compliant Tier 1 database. Tier 1 assigns ¥0 to KMS because cloud-product server-side encryption can use a free default service/master key; this must not be confused with a paid Software Key Management Instance, whose minimum listed price is ¥2,499/month. Tiers 2 and 3 continue to use public month-to-month planning prices: ECS `ecs.c5.2xlarge` (8 vCPU / 16 GiB) ¥1,092/month; ECS `ecs.g5.xlarge` (4 vCPU / 16 GiB) ¥600/month; 5 Mbps fixed bandwidth ¥125/month; RDS MySQL 4 vCPU / 16 GiB Basic Edition ¥480/month; and the corresponding High-availability Edition ¥1,360/month. SSD and RDS storage are charged separately. The public reference for OSS Standard LRS is ¥0.12/GB-month; the geographically redundant OSS example is ¥0.15/GB-month, while public download traffic is ¥0.50/GB in the published example.

These are public-reference or captured prices, not a locked quotation: selected region, CPU family, disk type, purchase term, RDS edition, backup retention, and traffic all change the bill; the captured ECS promotion may expire or be unavailable at renewal. Confirm TDE applicability to the Basic versus High-availability series again before placing the order. Sources: [ECS price list](https://ecs-buy.aliyun.com/price), [ECS public-bandwidth billing](https://help.aliyun.com/zh/ecs/public-bandwidth/), [RDS MySQL public configuration price table](https://developer.aliyun.com/article/1751050), [RDS MySQL TDE](https://help.aliyun.com/en/rds/apsaradb-rds-for-mysql/configure-tde-for-an-apsaradb-rds-for-mysql-instance), [OSS storage fees](https://help.aliyun.com/zh/oss/storage-fees), [OSS billing examples](https://help.aliyun.com/zh/oss/billing-examples), [KMS keys for cloud-product server-side encryption](https://help.aliyun.com/en/kms/key-management-service/user-guide/integration-with-kms/), and [KMS billing](https://help.aliyun.com/en/kms/key-management-service/product-overview/kms-billing).

This document is a simplified funding decision aid. The detailed component assumptions, capacity assumptions, and overseas comparison remain in [Deployment Cost Estimate](DeploymentCostEstimate.md). In particular, see its §2 for capacity assumptions and §5 for required go-live costs that are intentionally excluded from the headline totals. Its Alibaba Cloud component amounts predate this recalculation and should not be used in place of this guide’s figures.
