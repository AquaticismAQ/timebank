# Time Bank · Blockchain Evidence Storage Plan (Blockchain)

> This document is a sub-plan of the Time Bank Design (`01-TimeBankDesign.md`). It defines how blockchain is used for **evidence storage** in Time Bank. For review-data sources, see `02-ManualAudit.md` and `03-AutoAudit.md`; for the delivery platform, see `05-OnlineSystem.md`.

## 1. Positioning: evidence storage, not transaction

**Blockchain is used only to preserve evidence of volunteer-service activities and honors; it is not used for trading or value transfer.**

- **Evidence storage**: Write key information from approved service records (hash, time, and signature) on-chain so it is **tamper-resistant, traceable, and independently verifiable**.
- **Non-trading**: The system **does not issue any tokens (Tokens), does not mine, is not listed on exchanges, and is not linked to legal currency**.
- **Exchange is not considered a transaction**: The on-campus redemption of Time Coin/Love Points is an **internal point write-off** behavior within the school. It is only recorded in the centralized business library and **is not transferred on the chain and does not constitute a financial transaction**. There is only evidence of the fact that "the student has received a certain honor/completed a certain service" on the chain.

> In one sentence: **Store factual fingerprints on-chain; manage operational points off-chain.**

## 2. Why is blockchain needed?

| Pain points | The role of blockchain (TRUST model) |
| --- | --- |
| Internal personnel are entrusted to tamper with data | **T traceability**: record the digital signatures of reviewers and blind reviewers, lifelong accountability system |
| Backfill date and inflated duration before entering school | **R Anti-backfill**: The on-chain time is locked by the block height iron seal |
| Universities/third parties are difficult to accept | **U social-level credibility**: directly verified by TxHash in the on-chain browser, without manual back-checking |

> Key boundaries: **Blockchain cannot determine whether a child "has lied"** (that is the responsibility of auditing and AI risk control, see `03-AutoAudit.md`, `07-AI.md` for details). Its function is to lock the evidence chain after passing the audit, prevent subsequent tampering, and establish social credibility.

### 2.1 Digital Honor Certificate on the chain (verifiable certificate VC, non-NFT)

When the "time, location, desensitized photos, witness hashes" and other evidence are passed through the background review, the system automatically generates a unique "Public Welfare Practice Digital Honor Certificate" and writes its hash value and core metadata onto the chain.

- **Function**: Schools, college admissions, and the general public can **permanently check the authenticity of the certificate** with the block height/TxHash; no one (including system administrators and principals) can "backfill the date" or "inflate the duration."
- **Why use verifiable certificates (VC) instead of NFT**: NFT is essentially a **transferable and priceable on-chain token**, which touches the 94/924 regulatory red line (see section 2.2 of `06-Legal.md` for details). This project adopts the **non-transferable and non-tradable "Verifiable Credential" model** - it only proves the fact that "a certain student has completed a certain charity" and does not have any financial or circulation attributes. Externally, it is uniformly referred to as "digital certificate of honor/certificate of honor", and expressions such as "NFT/digital collection/token" are prohibited.

### 2.2 Witness Credit Record (Cross-Institution Anomaly Detection)



The witness’s mobile phone number and UnionID are put on the chain in the form of **salted hash** (see §4.2). Based on comparable hash fingerprints on the chain, the system can identify anomalies across schools and locations:

- The same witness hash has signed frequently for a large number of students from **different schools and locations** in a short period of time → It will be judged as a "professional witness/brush" and included in the blacklist (see `03-AutoAudit.md` §8.4 and `07-AI.md` §6 for risk control rules).
- On-chain evidence storage ensures that the credit file cannot be erased or tampered with by a single institution privately, making cross-school collaboration more credible.

### 2.3 Cross-school and social-level credibility

When the project is promoted to hundreds or thousands of schools, students’ charity time will need to be considered in college admissions or social evaluation. If the data only exists in a centralized server, it is easy for a third party to question "whether the data has been modified by a related user."

- **Function**: Blockchain is a natural audit tool, giving public welfare duration "social-level credibility" - any third party can independently verify it on the chain, and schools and parents cannot modify it privately. This is the trust infrastructure that supports the project from a single school to a regional/national scale.

## 3. Legal compliance requirements (subject to Chinese laws and regulations)

### 3.1 Core Red Line

- **No issuance and no trading**: Strictly avoid the regulatory red lines established by the Announcement on Preventing Risks from Initial Coin Offerings and the Notice on Further Preventing and Handling Risks of Speculation in Virtual Currency Transactions. Prohibited external expressions must not appear in system copy; use “honor evidence record” and “public-interest points” instead.


- **Use compliant alliance chain**: Abandon any public chain with native tokens (Ethereum, Solana, etc.) and adopt **domestic registered open alliance chain**.
- **Blockchain Information Service Registration**: According to the "Blockchain Information Service Management Regulations", the blockchain information service used should have completed the registration with the Cyberspace Administration of China; the school, as the application party, verifies the service provider's registration number.
- **Personal Information Protection**: In accordance with the "Personal Information Protection Law", "Minor Protection Law" and "Regulations on the Internet Protection of Children's Personal Information":
  - Processing of minors’ information requires **guardian consent**.
  - **Clear text personal information or identifiable portraits** are never written on the chain, only hashed fingerprints.
  - Implement the minimum necessary principle and data desensitization (see section 9 of `03-AutoAudit.md` for details).
- **Data export**: Link nodes and data storage should be within the country to avoid cross-border data compliance risks.

### 3.2 Subject and real name

- Complete enterprise-level real-name authentication (KYC) on the alliance chain platform with **schools, education authorities or legally registered non-governmental organizations (NGOs)** as the main body.
- Students and witnesses are **not required** to hold digital wallets or private keys.

### 3.3 Insensitive Gas (RMB settlement)

- The on-chain fuel fee is paid in batches by the project party/school on the backend using **RMB pre-deposit package**.
- The client has zero awareness and does not come into contact with any crypto assets.

## 4. Upchain data design

### 4.1 Principles of chaining

- **Only the hash, not the original text**: Original materials and personal information are stored in domestic business libraries/object storage, and only their SHA-256 fingerprints and necessary metadata are stored on the chain.
- **Verifiable and non-reversible**: Salted hashing of sensitive items such as mobile phone number, guardian signature, etc.
- **Batch upload**: Write in batches after passing the review to reduce costs and frequency.

### 4.2 Uplink fields (certificate package)

| Field | Description |
| --- | --- |
| Record number | Service/honor record unique ID |
| Student ID hash | Student ID hash (no clear text stored) |
| Service duration | Review and confirmation duration |
| Desensitization image Hash | Desensitization image SHA-256 |
| Witness mobile phone number Hash | Salted hash |
| Witness UnionID Hash | WeChat unique identifier encrypted hash (anti-Sybil: batch forging dozens of real-name WeChat credits is extremely costly) |
| Guardian Endorsement Hash | Salted Hash |
| Review teacher’s signature | Reviewer’s digital signature |
| Timestamp/block height | Iron seal of chaining time |

### 4.3 Upchain process

```mermaid
flowchart LR
A[Approved, see AutoAudit/ManualAudit for details] --> B[Backend assembly certificate package<br/>Hash sensitive fields]
B --> C [Batch call alliance chain SDK write]
C --> D[Return TxHash and block height]
D --> E[Business library backfill TxHash]
E --> F [Generate a verifiable digital certificate of honor]
F --> G [Third party verification on TxHash chain]
```

### 4.4 Hybrid architecture: front-end centralization + back-end blockchain

In order to facilitate promotion, a hybrid architecture of "**lightweight front-end + blockchain evidence storage backend**" is adopted, and **never let students and witnesses come into contact with any blockchain concepts**:

| Hierarchy | Form | Responsibilities |
| --- | --- | --- |
| User terminal (no sense) | Complete Web platform + third stage POV WeChat applet | The Web independently carries the entire process of projects, registration, records, audits and accounts; the applet supplements on-site check-in, witness scanning and parent endorsement. No private keys, no wallets, no crypto assets on either end |
| Business processing (centralized) | Centralized server | Initial review: GPS range comparison, AI image recognition and risk control, statistical anomaly detection (see `03-AutoAudit.md`, `07-AI.md` for details), quick response |
| Final evidence storage (blockchain) | Compliance alliance chain | The audited records will be packaged and "uplinked" to the chain in batches every week or every month to reduce frequency and cost |


- **Why is the front-end centralized**: Real-time review, low-latency interaction, and zero learning cost for users all need to be borne by the centralized system.
- **Why back-end blockchain**: Only make an "anti-tampering iron seal" after the review is finalized, and upload it to the chain in batches, without the need for real-time uploading.
- This architecture is consistent with Section 8 "Path A (Open Alliance Chain Hosting + Hash Only)". For details of the hosting system, see `05-OnlineSystem.md`.

## 5. Public chain/consortium chain selection

> The term "public chain selection" in this project refers to **domestic compliant open consortium chains**, not overseas public chains.

| Selection | Introduction | Advantages | Disadvantages |
| --- | --- | --- | --- |
| **Wenchang Chain (based on BSN, preferred)** | BSN official open consortium chain | National-level infrastructure, clear compliance, RMB billing, open license chain friendly to small and medium-sized applications, very low evidence storage cost | Ecology is relatively vertical, you need to be familiar with BSN access specifications |
| Ant Chain Open Alliance Chain | Ant Group | Complete documentation, mature SDK, and high degree of productization of certificates | Commercial billing, long-term cost assessment of schools |
| Changan Chain (ChainMaker) | Domestic independent and controllable open source chain | Independent and controllable, privatized deployment, strong performance | Self-built/hosted nodes are required, and the operation and maintenance threshold is high |
| Tencent Zhixin Chain | Tencent | Strong judicial evidence storage ecosystem, good connection with WeChat | Business cooperation threshold and billing need to be negotiated |

**Recommended: The first choice is Wenchang Chain (BSN Open Alliance Chain)**. With its national-level endorsement, clear compliance, RMB billing and extremely low evidence storage costs, it best meets the school's long-term, low-cost, and compliant evidence storage needs; Ant Chain is an alternative.

## 6. Cost Assessment

| Project | Estimate | Description |
| --- | --- | --- |
| Single evidence storage cost | About ￥0.01–￥0.05 | Open alliance chain on-chain / evidence storage call fee |
| Annual online volume (example) | Assume 1,000 students × 20 articles per year = 20,000 | Estimated by school size |
| Annual evidence storage fee (example) | About ￥200–￥1000 | 20,000 pieces × unit price, lower after batch size |
| Node/Access | Open alliance chain does not require self-building nodes | Choosing the hosting mode can save node operation and maintenance |
| Object storage/business library | Additional | Desensitization map and business data storage costs, see `05-OnlineSystem.md` for details |

> Key points of cost control: **Bulk uploading**, **Only hashing**, **Priority to open alliance chain hosting mode**, to avoid the investment in hardware and operation and maintenance of self-built nodes.

## 7. Development difficulty and ease of use analysis

| Dimensions | Assessment |
| --- | --- |
| Access difficulty | Medium to low. The open consortium chain provides RESTful/SDK, which can be called by the backend without any concern to front-end users |
| Private keys | Users do not need private keys; keys are managed by the project backend and require appropriate security controls |
| Learning cost | Mainly to become familiar with the evidence storage API and filing process of the selected chain |
| Operation and maintenance | There is almost zero node operation and maintenance under the hosting mode; the operation and maintenance cost of privatized deployment (Chang'an chain) is high |
| Ease of use | Full transparency to students/witnesses/teachers, backend integration only |

## 8. Analysis and recommendation of pros and cons of implementation path

| Path | Advantages | Disadvantages | Applicability |
| --- | --- | --- | --- |
| **A. Open consortium chain hosting + hashing only (recommended)** | Clear compliance, zero node operation and maintenance, extremely low cost, no user awareness | Relying on service provider availability | Long-term low-cost evidence storage for schools |
| B. Self-built/privatized alliance chain (Chang'an Chain) | Independent and controllable, completely self-sustaining data | Node operation and maintenance and hardware costs are high and the cycle is long | Large institutions or multi-school alliances |
| C. Third-party judicial evidence storage SaaS (Zhixin Chain, etc.) | Strong judicial acceptance, mature products | Commercial billing, limited flexibility | Scenarios that emphasize judicial effectiveness |
| D. Does not use blockchain, only centralized database + digital signature | Lowest cost and simplest | Tamper-proof and weak third-party credibility | Early stage of trial operation |

**Recommended evolution route:**

- **Early trial operation**: You can first use path D (centralization + digital signature + operation log) to verify the business.
- **Formal Stage**: Adopt **Path A (Wenchang Chain Open Alliance Chain Hosting + Hash Only + Batch Uplink)** as a long-term solution.
- **Forward (Multi-School/Judicial Need)**: Assessment for upgrade to Path B or overlay Path C.

- The final decision can be configured in any one of ABC

## 9. Connection with other solutions

- The on-chain data comes from the audited records of `02-ManualAudit.md` and `03-AutoAudit.md`.
- Only hash fingerprints remain on the chain; desensitized maps, business data and time coin accounts are managed by the centralized system described in `05-OnlineSystem.md`.
- Time coins are exchanged for on-campus points and are written off, **not uploaded to the chain, and do not constitute a transaction**.
