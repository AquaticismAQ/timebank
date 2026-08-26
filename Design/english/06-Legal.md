# Time Bank · Legal Compliance Solution (06-Legal)

> This document is a sub-plan of "Time Bank Design" (`01-TimeBankDesign.md`). It systematically discusses the legal constraints, limit boundaries and legalization paths that time banks face when using blockchain and "time coin" under **China's current laws and regulations**. For the implementation of blockchain technology, see `04-Blockchain.md`, and for data and privacy processing, see `03-AutoAudit.md` and `02-ManualAudit.md`.
>
> **Disclaimer**: This article is a compliance design reference and informal legal opinion. Before the project is officially implemented, a special review should be conducted by the school's legal affairs or professional lawyers based on the latest regulations and local regulatory standards.

## 1. Overall Compliance Positioning

Time Bank’s compliance position can be summarized in one sentence: **It is an internal educational system for public-interest honors and points—not a financial instrument or any form of currency.**

- **Blockchain is used only for evidence storage**, recording factual fingerprints of volunteer service and honors; **it is not used for trading or issuing transferable value**.
- **A “time coin” is only an internal school accounting unit for points**. It cannot be cashed out or transferred, is not linked to legal tender, and cannot enter secondary circulation.
- **Exchange is a write-off of on-campus points** and does not constitute payment, settlement or financial transaction.

As long as the three red lines of "non-monetary, non-trading, and non-financial" are adhered to, the overall legal risk can be controlled.

## 2. Legality constraints of blockchain applications

### 2.1 Blockchain information service registration (mandatory)

- **Basic**: "Blockchain Information Service Management Regulations" (State Cyberspace Administration of China, 2019).
- **Requirements**: Entities that provide blockchain information services to the public must **perform the filing procedures** and obtain a filing number.
- **Implementation**: As the application party, the school should verify that the selected blockchain service provider (such as BSN/Wenchang Chain, Ant Chain, etc.) has completed the registration and retain its registration number; if the self-built chain provides external services, it must register itself.

### 2.2 Token issuance and virtual currency transactions are strictly prohibited (highest red line)

- **Basis**:

  - “Announcement on Preventing Risks from Initial Coin Offerings” (seven ministries and commissions, 2017; the “94 Announcement”).
  - “Notice on Further Preventing and Handling Risks of Speculation in Virtual Currency Transactions” (ten ministries and commissions, 2021; the “924 Notice”).
- **BANNED**: Issuance of any tokens (Token), Initial Coin Offering (ICO), mining, virtual currency exchange/trading/pricing, exchange with fiat currency or foreign exchange.
- **Implementation**: The system **will never issue coins, mine, or be listed on exchanges**; time coins must not have any characteristics of virtual currencies (see Section 3 for details).

### 2.3 Compliance alliance chains must be used and public chains must be abandoned.

- **Reason**: Public chains with native encryption tokens such as Ethereum and Solana naturally involve virtual currencies and touch the 94/924 red line.
- **Implementation**: Adopt **domestic registered open alliance chain** (Wenchang Chain/BSN is recommended, Ant Chain is an alternative, etc.), the fuel fee is settled in **RMB**, and the client has no private key, no wallet, and no encrypted assets (see `04-Blockchain.md` for details).

### 2.4 Data Security and Domestic Storage

- **Basis**: "Cybersecurity Law", "Data Security Law" and "Personal Information Protection Law".
- **Requirements**: Blockchain nodes and data storage should remain **within mainland China**; avoid cross-border data transfers; and implement data classification and security-protection obligations.
- **Implementation**: **only hash fingerprints** are stored on the chain, and no plain text personal information is stored; original data and desensitized images are stored in the domestic business library/object storage.

### 2.5 Judicial effect of blockchain evidence (forward use)

- **Basic**: The "People's Court Online Litigation Rules" (2021) and judicial practice (Hangzhou Internet Court, etc.) confirm that electronic data stored and fixed through trusted technologies such as blockchain can be admitted as evidence if they meet the conditions.
- **Value**: On-chain certification can improve the tamper resistance and third-party credibility of volunteer service and honor records (see the TRUST model of `04-Blockchain.md` for details).

## 3. Legal restrictions faced by “Time Coin”

“Time coin” is the most sensitive area of compliance risk. The core requirement is to **prevent it from being classified as a virtual currency, transferable value certificate, or prepaid card**.

### 3.1 Shall not constitute "virtual currency/tokens"

- It may not be issued, mined, priced, or exchanged with legal currency or virtual currency.
- Financial attributes such as "value-added", "financial management" and "investment" are not allowed.

### 3.2 Must not have financial/payment attributes

- **Basic**: "Measures for the Administration of Payment Services of Non-Financial Institutions" and "E-Commerce Law".
- **Restrictions**: Time coins may not be used for **external payments, settlements, change, and cash withdrawals**, and may not be **freely transferred and circulated** between students or with entities outside the school.
- **Note on Loan Function**: The "Time Coin Loan" in `01-TimeBankDesign.md` should be strictly characterized as an accounting advance for **on-campus service hours**, which does not accrue interest, does not involve funds, and cannot be cashed, so as to avoid being interpreted as a credit/financial business.

### 3.3 Avoidance of regulation on prepaid cards/single-purpose consumer cards

- **Basic**: "Administrative Measures for Single-Purpose Commercial Prepaid Cards (Trial)" (Ministry of Commerce).
- **Risk**: If Time Coin can be widely redeemed for physical goods and has stored value/prepaid features, it may be regulated by analogy with prepaid cards.
- **Implementation**: The redemption is mainly based on **honors, growth incentives, and non-profit rights on campus**, weakening the "shopping stored value" attribute; it is not linked to cash recharge.

### 3.4 Personal Information and Protection of Minors

- **Basic**: "Personal Information Protection Law", "Minor Protection Law", "Provisions on the Internet Protection of Children's Personal Information".
- **Requirements**: Processing of personal information of minors (especially children under 14 years old) must obtain **guardian consent**; follow the minimum necessary principle; no clear personal information or identifiable portraits should be written on the chain.
- **Implementation**: Sensitive items such as the witness’s mobile phone number, UnionID, and guardian’s signature are **only stored in encrypted hashes**; the device is desensitized and the original image is not saved on the disk (see `03-AutoAudit.md` for details).

### 3.5 Prudence in connection with further education/excellence evaluation

- When time coins are linked to comprehensive quality evaluation and admission materials, the evaluation must be guaranteed to be **authentic, fair, and traceable** to prevent rent-seeking; high-value certificates should be combined with cross-school blind evaluations (see `03-AutoAudit.md`) and comply with relevant regulations of the education authorities.

### 3.6 Terminology compliance (external presentation)

The following expressions are **disabled** in the entire system: `Token, token, digital currency, cryptocurrency, mining, cash withdrawal, wallet, currency speculation, value-added financial management`.
The unified name to the outside world is: **"Public Welfare Points/Charity Points/Service Hours/Honor Certificate/Growth File"**.

## 4. How to legalize (compliance design path)

| Compliance Points | Specific Practices |
| --- | --- |
| **Defined as points rather than currency** | The charter clearly states that "time coin = campus charity points/honorary accounting unit" and has no financial attributes |
| **Keep the Three Red Lines** | No currency issuance, no trading, no cash withdrawals; no transferability, no cash redemption, and no linkage to legal currency |
| **The blockchain only stores certificates but does not trade** | The alliance chain only writes hashes to store certificates; the exchange is recorded in the centralized business library and does not transfer on the chain (see `04-Blockchain.md`) |
| **Use the registered alliance chain** | Use the registered domestic open alliance chain, bill in RMB, and the user does not have a private key |
| **Real name of subject** | With school/education authority/legally registered NGO as the operating entity, complete enterprise-level KYC |
| **Guardian consent** | Obtain guardian authorization for registration and information processing, and provide privacy policy and notice |
| **Data Minimization and Domesticization** | Only hashes are stored on the chain; only hashes are encrypted for sensitive fields; data and nodes are within the country |
| **De-financialization of redemption** | Redemption is limited to honors, growth incentives, and on-campus non-profit rights. No stored value or cash recharge is allowed |
| **De-crediting of lending** | Loans are characterized as time-based accounting advances, with no interest and no capital involved |
| **Terminology Specifications** | Token-type expressions are prohibited internally and externally, and "points/honors/certificates" are used uniformly |
| **Legal Review** | Before implementation, the school's legal affairs/lawyers will conduct a special compliance review and retain compliance documents |

## 5. Compliance Checklist (Self-inspection before going online)

- [ ] Are there **no tokens** issued, no mining, and no virtual currency exchange?
- [ ] Has the selected blockchain service provider completed registration with the Cyberspace Administration of China and retained the registration number?
- [ ] Is there only hash fingerprints stored on the chain, no clear text personal information and portraits?
- [ ] Is Time Coin **non-cashable, non-transferable, and not linked to legal currency**?
- [ ] Is the redemption limited to honorary/non-profit benefits and **does not have the stored value prepayment feature**?
- [ ] Obtain **guardian consent** and provide a privacy policy?
- [ ] Are the data and link nodes **all located within the country**?
- [ ] Has sensitive expression about tokens been removed from external copywriting?
- [ ] Has the **Legal/Lawyer Special Review** been completed and kept on file?

## 6. Connection with other solutions

- For blockchain technology implementation, public chain selection and costs, see `04-Blockchain.md`.
- For data desensitization, witness metadata and privacy processing, see `03-AutoAudit.md` and `02-ManualAudit.md`.
- For the system rules of time coin, exchange and lending, see `01-TimeBankDesign.md`.
- For the implementation of data security in online systems, see `05-OnlineSystem.md`.
