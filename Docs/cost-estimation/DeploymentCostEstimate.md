# TimeBank Deployment Cost Estimate

> **Planning baseline, not a supplier quotation.** Prepared 2026-08-20. All prices are public-list-price planning inputs, before tax, promotions, negotiated education discounts, and foreign-exchange changes. Recheck the provider calculator and obtain a written quote immediately before procurement.

## 1. Decision Summary

This estimate covers one school with **3,000 students, 300 staff accounts, and 300 peak concurrent users**. The concurrency target is the same as the existing technical design; its expected sustained peak is about 60 requests/s and its burst target is about 200 requests/s. Evidence uploads go directly to object storage and therefore do not traverse the application server.

Two options are shown for each location:

| Option | Purpose | Recommendation |
| --- | --- | --- |
| Single server | Lowest-cost pilot or non-critical environment; application, Nginx, and MySQL share one host | **Do not use for a school production launch.** A host failure stops both the application and database. |
| Dual-server hot standby | Two hosts in one region/AZ set: each runs an application instance; MySQL primary replicates asynchronously to the second host; manual promotion on failure | **Minimum production option.** It enables B7 backup/restore and failover work, but is not automatic HA and needs a load balancer plus operational rehearsal. |

The current repository design requires domestic data and nodes for the intended mainland-China school deployment. An overseas deployment is therefore a separate jurisdiction/data-residency option, not a failover region for the mainland system. It needs a new institutional and legal approval before any student data is transferred overseas.

## 2. Common Sizing Assumptions

| Item | Sizing used in this estimate | Why |
| --- | --- | --- |
| Compute per production host | 8 vCPU, 32 GiB RAM, Linux | Leaves room for Spring Boot, Nginx, MySQL, OS cache, and a 60–70% MySQL buffer pool on a co-hosted minimum-production host. |
| Local SSD per host | 600 GB SSD: 100 GB OS/application/logs + 500 GB MySQL/binlogs/headroom | The designed steady-state database is only about 10–20 GB, but the host must also hold binlogs, upgrades, imports/exports, and recovery headroom. |
| Application database pool | 20–30 connections per application instance in production | Existing configuration explicitly says the current pool of 5 is only for the undersized shared test host. |
| Public edge | 10 Mbps committed outbound bandwidth in mainland China; at least 1 Gbps private-network capability between hosts | 10 Mbps is sufficient for API/UI traffic because evidence uses direct object-storage upload/download. Observe and raise it if non-file traffic approaches 70% sustained use. |
| Object storage | Private bucket, pre-signed upload/download URLs, lifecycle: Standard → IA after 90 days → Archive after 1 year | This is the planned B3F replacement for local evidence disk storage. |
| Evidence capacity allowance | 700 GB at steady state; 100 GB/month public-download allowance | Existing design estimates 550–700 GB of retained evidence after lifecycle purge. The estimate budgets the conservative upper end. |
| E-mail volume | 180,000 recipient-deliveries/year = 15,000/month | Scales the existing 300,000/year design figure from 5,000 to 3,000 students. Each recipient is billable separately. |
| Backups | 100 GB average billed object/snapshot capacity initially, then validate from measured compressed backups | Nightly physical backup plus binlog shipping must be implemented and restore-tested; this is deliberately an allowance, not evidence that backups already exist. |

Do not treat these figures as proof that the required 300-user load test has passed. The load test, restore drill, replication-lag monitoring, and staging rehearsal remain required acceptance work.

## 3. Mainland China: Alibaba Cloud Reference Estimate

### 3.1 Proposed topology

Use one domestic Alibaba Cloud region nearest the school. Keep MySQL private (no public IP), put the second host in another availability zone where available, and use a private OSS bucket in the same region. The public endpoint terminates TLS at Nginx/load balancer. DirectMail is the outbound e-mail service.

| Option | Hosts and roles | Network |
| --- | --- | --- |
| Single server | 1 × ECS 8 vCPU/32 GiB/600 GB SSD running Nginx + Spring Boot + MySQL | 10 Mbps public outbound |
| Dual-server hot standby | 2 × ECS 8 vCPU/32 GiB/600 GB SSD. Host A: application + MySQL primary. Host B: application + asynchronous MySQL replica/standby. | 10 Mbps public edge; private VPC replication; load balancer/DNS switch for app traffic |

### 3.2 Monthly cost model (CNY, before tax)

| Cost item | Single server | Dual-server hot standby | Calculation / note |
| --- | ---:| ---:| --- |
| ECS compute | ¥1,200 | ¥2,400 | Reference public rate: an Alibaba Cloud `ecs.g5.2xlarge` (8 vCPU/32 GiB) is listed at ¥1,200/month. Use the current-generation equivalent in the selected region at purchase time. |
| SSD cloud disks | ¥480 | ¥960 | Planning input: 600 GB × ¥0.80/GB-month, derived from the published SSD-disk example. |
| Public bandwidth | ¥525 | ¥525 | 10 Mbps fixed-bandwidth reference. The standby is private and does not need a public IP. |
| Load balancer / failover entry point | — | ¥100 | Conservative planning allowance; validate Alibaba Cloud ALB/SLB price and chosen cross-zone configuration in the calculator. |
| OSS evidence capacity | ¥84 | ¥84 | Conservative upper bound: 700 GB × ¥0.12/GB-month, all Standard/LRS. Lifecycle tiering should reduce this, but is not counted as a saving until configured. |
| OSS public download traffic | ¥50 | ¥50 | 100 GB/month × ¥0.50/GB reference. Upload and intra-region traffic should be checked separately in the OSS calculator. |
| Backup/PITR object capacity | ¥12 | ¥12 | 100 GB × ¥0.12/GB-month planning allowance. Actual XtraBackup/binlog retention may be higher. |
| DirectMail | ¥30 | ¥30 | 15,000 recipient-deliveries/month × ¥2/1,000; package selection can change this. |
| **Estimated monthly recurring total** | **¥2,381** | **¥4,161** | Excludes VAT, WAF/CDN, domain/DNS, monitoring, staging, and labour. |
| **Estimated annual recurring total** | **¥28,572** | **¥49,932** | Monthly total × 12; not a multi-year subscription quote. |

### 3.3 Mainland pricing evidence and constraints

- Alibaba's ECS public price list shows the 8 vCPU/32 GiB `ecs.g5.2xlarge` reference at ¥1,200/month and SSD disk examples at ¥0.80/GB-month. Prices vary by region, generation, OS, contract term, and promotions: [ECS price list](https://ecs-buy.aliyun.com/price).
- Alibaba's 10 Mbps Hangzhou fixed-bandwidth example is ¥525/month; its page also gives a reference traffic price of ¥0.80/GB. Select fixed bandwidth for stable school traffic or pay-by-traffic only after measuring actual demand: [ECS public-bandwidth billing](https://help.aliyun.com/en/ecs/public-bandwidth).
- OSS Standard/LRS is documented at ¥0.12/GB-month; a published example uses ¥0.50/GB for public outbound traffic. Object requests, retrieval, and early-deletion charges are separate: [OSS billing overview](https://help.aliyun.com/zh/oss/billing-overview) and [OSS billing example](https://help.aliyun.com/zh/oss/billing-examples).
- DirectMail pay-as-you-go is ¥2 per 1,000 recipient-deliveries. Its 50,000-message package costs ¥90 and lasts six months; messages beyond a package revert to pay-as-you-go. The initial per-account daily quota must also be confirmed/raised before launch: [DirectMail billing](https://help.aliyun.com/en/direct-mail/billing-methods) and [service limits](https://help.aliyun.com/zh/direct-mail/product-overview/limits).

### 3.4 Mainland operational notes

1. The dual-host estimate is **warm/hot standby with manual MySQL promotion**, not an automatic failover cluster. It cannot guarantee zero data loss because replication is asynchronous. Measure actual RPO/RTO in the required restore/failover drill.
2. Run backups from the replica, ship binlogs to OSS, and restore weekly into a scratch instance. Add the temporary scratch-host cost during verification; it is not safely optional.
3. Evidence and large exports must use OSS pre-signed URLs. Do not increase ECS bandwidth merely to proxy files through the application.
4. Use DirectMail API for ordinary transactional notices. It supports attachments through SMTP only; sensitive evidence should remain an authenticated TimeBank link rather than an attachment.

### 3.5 Mainland managed MySQL alternative: Alibaba Cloud RDS

For production, the managed-database comparison should use **RDS for MySQL High-availability Edition**, not Basic Edition. The high-availability edition keeps a synchronous standby in another zone and is the service-managed alternative to the self-hosted primary/async-replica pair. A single-AZ RDS instance is useful for development only; it does not solve the production database availability risk.

| Component | Initial managed-DB sizing | Monthly planning band (CNY, before tax) | Notes |
| --- | --- | ---:| --- |
| RDS MySQL, Single-AZ | 4 vCPU / 16 GiB, 200 GB ESSD PL1 | ¥1,200–¥1,800 | Development or cost comparison only; no standby. |
| RDS MySQL, High-availability Edition | 4 vCPU / 16 GiB, 200 GB ESSD PL1, multi-zone synchronous standby | ¥2,600–¥3,600 | Recommended starting production tier. The final amount varies by region, instance family, storage type, backup retention, and subscription term. |
| Backup/PITR overage | Automated backup + log retention beyond included quota | ¥100–¥300 | Must be validated after retention and measured backup size are chosen. |
| Two application ECS hosts | 2 × 4 vCPU / 16 GiB / 100 GB SSD | ¥1,360 | ¥1,200 compute + ¥160 disks, using the same public ECS reference rate. |
| Shared edge and other services | 10 Mbps edge, load balancer, OSS capacity/download/backup allowance, DirectMail | ¥843 | ¥525 + ¥100 + ¥84 + ¥50 + ¥12 + ¥30. |
| **Managed production total** | Two application hosts + RDS HA + shared services | **¥4,903–¥6,103/month** | Compared with ¥4,161/month for the self-hosted dual-server reference. |
| **Managed production annual total** | Monthly total × 12 | **¥58,836–¥73,236/year** | Before tax and commitment discounts. |

The RDS high-availability estimate is roughly **18–47% higher** than the self-hosted dual-server reference, but it removes responsibility for maintaining the database standby, performs managed failover, and includes provider-managed backup/maintenance capabilities. It does **not** remove the need to test restore, validate RPO/RTO, manage MySQL users/schema migrations, monitor application query performance, or protect the application hosts.

Alibaba Cloud does not publish one stable universal price for this RDS shape: the price is selected in the RDS buy page after region, edition, instance class, storage, and billing method are chosen. Its current documentation confirms subscription, pay-as-you-go, and serverless models; it also exposes itemized pricing in the console. Quote the exact High-availability Edition configuration before approval: [create an RDS MySQL instance](https://help.aliyun.com/en/rds/apsaradb-rds-for-mysql/create-an-apsaradb-rds-for-mysql-instance-1), [RDS billing](https://help.aliyun.com/en/rds/apsaradb-rds-for-mysql/billing-1), and [RDS pricing-details feature](https://help.aliyun.com/en/rds/apsaradb-rds-for-mysql/new-features-new-specifications-apsaradb-rds-releases-the-pricing-details-feature).

## 4. Overseas: AWS Singapore Reference Estimate

### 4.1 Why AWS Singapore was selected

AWS Singapore is used as a concrete overseas reference because EC2, EBS, S3, Elastic Load Balancing, and SES cover the same required roles. It is an estimate for an overseas institution or a separately approved deployment; it is not a recommendation to move the mainland school system overseas.

| Option | Hosts and roles | Network |
| --- | --- | --- |
| Single server | 1 × EC2 class equivalent to 8 vCPU/32 GiB, 600 GiB gp3 EBS; Nginx + Spring Boot + MySQL co-hosted | Public endpoint; 100 GB/month internet egress allowance |
| Dual-server hot standby | 2 × equivalent EC2 hosts, each 8 vCPU/32 GiB and 600 GiB gp3. Primary host runs MySQL primary; second host runs asynchronous replica; application runs on both. | Application Load Balancer, private VPC replication, 100 GB/month internet egress allowance |

### 4.2 Monthly cost model (USD, before tax)

The model uses an on-demand **planning allowance of US$370/month** for one 8 vCPU/32 GiB EC2 instance in Singapore. Confirm the exact currently offered instance family, operating system, and Savings Plan/Reserved Instance price in the AWS Pricing Calculator; the allowance is intentionally rounded because AWS changes instance families and regional rates.

| Cost item | Single server | Dual-server hot standby | Calculation / note |
| --- | ---:| ---:| --- |
| EC2 compute | $370 | $740 | 1 or 2 × $370 planning allowance for an 8 vCPU/32 GiB on-demand host. |
| EBS gp3 SSD | $48 | $96 | 600 GiB/host × $0.08/GiB-month baseline gp3 planning rate; uses included 3,000 IOPS / 125 MiB/s baseline. |
| Public egress | $12 | $12 | 100 GB/month planning allowance; confirm Singapore transfer tier in calculator. |
| Application Load Balancer | — | $25 | Planning allowance for hourly ALB plus light LCU use. |
| S3 evidence storage | $18 | $18 | 700 GB × approximately $0.025/GB-month planning rate before lifecycle-class savings. |
| Snapshot / backup object capacity | $6 | $6 | 100 GB/month allowance; validate snapshot and PITR retention in calculator. |
| Amazon SES | $2.40 | $2.40 | 15,000 messages × $0.16/1,000 on the current Essentials-plan rate. |
| **Estimated monthly recurring total** | **$456.40** | **$899.40** | Excludes tax, WAF/CDN, Route 53/domain, monitoring, staging, and labour. |
| **CNY planning equivalent** | **¥3,286** | **¥6,476** | Uses a budgeting assumption of ¥7.20/US$; this is not an exchange-rate forecast. |
| **Estimated annual recurring total** | **$5,477** | **$10,793** | Monthly total × 12 before commitment discounts. |

### 4.3 Overseas pricing evidence and constraints

- EC2 is billed by selected instance type and region; AWS publishes on-demand pricing and directs final architecture pricing through its calculator: [EC2 on-demand pricing](https://aws.amazon.com/ec2/pricing/on-demand/).
- gp3 charges for provisioned capacity and includes a 3,000 IOPS / 125 MiB/s baseline before additional performance charges: [EBS pricing](https://aws.amazon.com/ebs/pricing/) and [gp3 guidance](https://docs.aws.amazon.com/prescriptive-guidance/latest/optimize-costs-microsoft-workloads/ebs-migrate-gp2-gp3.html).
- S3, request, retrieval, and internet-egress charges are metered independently. The $18 line is a capacity allowance only; it does not include S3 request/retrieval costs: [S3 pricing](https://aws.amazon.com/s3/pricing/).
- SES Essentials currently lists $0.16/1,000 emails for 0–10 million emails/month. Attachment data is separately charged, so ordinary notification emails should not carry evidence files: [SES pricing](https://aws.amazon.com/ses/pricing/).

### 4.4 Overseas operational notes

1. Use AWS SES rather than a Google Workspace mailbox as the system-mail provider. It is purpose-built for application delivery, quotas, bounces, and deliverability; Google Workspace is better treated as staff mail unless the institution specifically mandates its SMTP relay/Gmail API.
2. For 300 concurrent users, do not use spot instances for either MySQL host. A Savings Plan or Reserved Instance is appropriate only after the load test and host sizing are proven.
3. Place the database on private subnets; allow traffic only from application security groups. Do not expose MySQL to the internet.
4. Test overseas latency from the actual user population. If most users are in mainland China, overseas delivery can have user-experience, routing, and compliance risks beyond the cloud bill.

### 4.5 Overseas managed MySQL alternative: Amazon RDS for MySQL

Use **Amazon RDS for MySQL Multi-AZ (one standby)** for the production comparison. It maintains a synchronous standby in a second Availability Zone and automatically fails over for planned or unplanned outages. This is materially stronger than the self-hosted asynchronous-replica design, although the standby is not a read-scaling endpoint in the one-standby model.

| Component | Initial managed-DB sizing | Monthly planning band (USD, before tax) | Notes |
| --- | --- | ---:| --- |
| RDS MySQL, Single-AZ | 4 vCPU / 16 GiB class, 200 GiB General Purpose SSD | $420–$540 | Development or comparison only; no standby. |
| RDS MySQL, Multi-AZ (one standby) | 4 vCPU / 16 GiB class, 200 GiB General Purpose SSD | $840–$1,080 | Recommended starting production tier; includes both DB instance-hours and storage for primary/standby, but not all backup overage. |
| Two application EC2 hosts + disks | 2 × 4 vCPU / 16 GiB / 100 GiB gp3 | $386 | $370 compute planning allowance + $16 gp3. |
| Shared edge and other services | 100 GB egress, ALB, S3 capacity, snapshot allowance, SES | $63.40 | $12 + $25 + $18 + $6 + $2.40. |
| **Managed production total** | Two application hosts + RDS Multi-AZ + shared services | **$1,289.40–$1,529.40/month** | Compared with $899.40/month for the self-hosted dual-server reference. |
| **CNY planning equivalent** | At ¥7.20/US$ | **¥9,284–¥11,012/month** | Budget conversion only, not an exchange-rate forecast. |
| **Managed production annual total** | Monthly total × 12 | **$15,473–$18,353/year** | Before tax and commitment discounts. |

The RDS Multi-AZ estimate is about **43–70% higher** than the self-hosted dual-server reference. The premium buys synchronous standby replication and automatic database failover, while avoiding self-managed MySQL host patching, replica promotion, and physical-backup operations. It does not eliminate application-host HA, schema-change discipline, least-privilege database access, query/index monitoring, or restore tests.

AWS bills both primary and standby instance-hours and storage in a Multi-AZ deployment; AWS notes that this commonly costs about twice Single-AZ for the database layer. Exact Singapore prices must come from the AWS Pricing Calculator using the selected current instance family and storage configuration: [RDS for MySQL pricing](https://aws.amazon.com/rds/mysql/pricing/) and [Multi-AZ billing guidance](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/USER_OnDemandDBInstances.html).

## 5. Costs Not Included in the Totals but Required for Go-live

| Item | Why it is required | Budget treatment |
| --- | --- | --- |
| Staging environment | Required to rehearse MySQL patching, restore, and failover safely | At least a smaller 4 vCPU/16 GiB host plus storage continuously; temporarily match production during the 300-user load test. |
| Restore scratch host | Required for weekly automated restore verification | Short-lived, but must be budgeted every week. |
| Monitoring and alerting | Replication lag, disk capacity, backup failure, pool saturation, and error-rate alerts are still missing | Use provider monitoring plus a log/metrics stack; obtain service-specific quote after retention is chosen. |
| WAF/CDN/DDoS, DNS, domain, certificates | Internet-facing school system protection and routing | Price depends on domain, traffic, and security policy. |
| E-mail implementation | Provider adapter, outbox/retry model, templates, bounces, suppression, and password-reset delivery | Engineering/QA cost, not a cloud consumption fee. |
| One-time migration and test data | Cloud-file-storage migration, existing evidence transfer, performance test dataset | Estimate after B3F migration plan and data volume are measured. |
| Taxes, ICP filing and legal review | Mainland public service requirements and minors' data processing; overseas data transfer may need separate approval | Explicitly excluded; procure with school/legal/finance owners. |

## 6. Procurement Gate and Next Actions

1. Confirm the school location, whether mainland data residency is mandatory, target RPO/RTO, and who operates incidents.
2. Request current quotes for the selected region and term. Replace every planning allowance in this document with quote IDs and date-stamped calculator exports.
3. Provision **staging first**, then dual-server production, OSS/S3, DirectMail/SES, private networking, and monitoring. Do not make the single-server pilot the production baseline by inertia.
4. Complete B3F object-storage migration and B7 backup/PITR/restore/failover rehearsal before accepting real school evidence.
5. Run the required 300-concurrent-user load test and adjust CPU, RAM, disk IOPS, and public bandwidth from measured p95 latency, database metrics, and object-storage traffic.
