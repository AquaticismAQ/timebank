# Time bank · Manual audit plan (02-ManualAudit)

> This document is a sub-plan of "Time Bank Plan Design" (`01-TimeBankDesign.md`), describing the **current stage** of the volunteer service review mechanism based on paper forms and manual verification, and defining the data fields of service records. For the automated audit plan, see `03-AutoAudit.md`, for on-chain evidence storage, see `04-Blockchain.md`, and for the online system, see `05-OnlineSystem.md`.

## 1. Scope of application and positioning

The manual review plan is suitable for the transitional stage before the online system is launched, and also serves as a backup and review method for the automatic review plan. Its core features are:

- **The paper “Student Volunteer Service Record Form” is used as the original supporting document.**
- **The organizer’s supervisor signs it, the grade-level teacher conducts the initial review, and the Student Development Center conducts the final review.**
- **Approved results are entered in the electronic ledger as the basis for crediting time coins.**

Manual review emphasizes that the process is controllable, the evidence can be checked, and the responsibility is assigned to the person. It is the first stage plan for the implementation of the system.

## 2. Data design

Manual and automatic solutions share the following data structure to facilitate smooth migration to electronic ledgers and online systems in the future.

### 2.1 Basic student information

| Field | Description |
| --- | --- |
| Student name | Student name in Chinese or English |
| Student ID | Student Unique Identification Number |
| Class | Current class |
| Grade | Current grade |
| Email | Student or school email |
| Telephone | Contact number, optional |

### 2.2 Service record information

| Field | Description |
| --- | --- |
| Service record number | Unique number for each record |
| Service Date | Actual Service Date |
| Start time | Service start time |
| End time | Service end time |
| Total duration | In hours, supports 0.5 hours |
| Whether to include travel time | If included, please indicate the reason |
| Service location | Specific address on or off campus |
| Organization name | School, community, charity organization, etc. |
| Organization type | Non-profit, school activities, government agency, other |
| Nature of service | Activity assistant, tutor, environmental protection, food distribution, etc. |
| Service description | Description of tasks actually completed by students |
| Name of supervisor | Name of organizer or project leader |
| Supervisor contact information | Telephone or email |
| Certification materials | Signatures, photos, emails, certificates, etc. |
| Review status | Pending review, passed, returned, rejected |
| Reviewer | Grade teacher or student development center teacher |
| Review time | Review completion date |
| Time coins credited | Number of time coins converted after approval |

### 2.3 Time coin account information

| Field | Description |
| --- | --- |
| Current balance | Time coins that have been reviewed and not redeemed or frozen |
| Cumulative acquisition | Accumulated time coins earned by students in history |
| Cumulative redemption | Student history cumulative redemption of time coins |
| Borrowed amount | Loan time coins that have not yet been returned |
| Frozen quantity | Time coins that are temporarily unavailable due to review, dispute or rule restrictions |
| Graduation requirements progress | Current cumulative time/stage requirement time |

## 3. Manual review process

```mermaid
flowchart LR
A[Students complete services] --> B[Fill in paper record form]
B --> C [Signature and confirmation by the organizer's supervisor]
C --> D [Submit to the teacher in charge of the grade]
D --> E{Preliminary review by grade teacher}
E -- Pass --> F [Review by Student Development Center]
E -- Return --> B
F -- Pass --> G [Enter the electronic ledger and enter the time coin]
F -- Rejection --> H [Record the reasons for rejection and notify students]
G --> I [Update cumulative duration and progress]
```

### 3.1 Step instructions

- **Step 1·Service Completion and Recording**: After completing the service, students should fill in the "Student Volunteer Service Record Form".
- **Step 2 · Signature from the organizer**: The organizer or project leader verifies the service duration and content and signs, leaving contact information.
- **Step 3·Submit for preliminary review**: Students submit the paper form and supporting materials to the teacher in charge of the grade; for off-campus services, it is recommended to attach photos, emails, and organizational certificates.
- **Step 4·Preliminary review by the grade teacher**: Check the completeness of the information, validity of the signature and project compliance.
- **Step 5· Review by the Student Development Center**: Randomly check the authenticity, confirm the reasonableness of the duration, and decide whether to include it in the account.
- **Step 6 · Account Entry and Statistics**: After approval, enter the record in the electronic ledger, convert the service time into time coins, and update cumulative progress.


## 4. Audit standards

Auditors should focus on checking:

- Whether the service content falls within the school's approved scope.
- Whether the service time is reasonable and matches the task content.
- Is the form information complete?
- Whether the signature of the organizer or supervisor is valid and verifiable.
- Whether there are duplicate submissions or obvious anomalies.
- Whether there are sufficient supporting documents for off-campus service.

## 5. Review status

| Status | Meaning |
| --- | --- |
| Pending submission | Student recorded but not submitted |
| Pending review | Materials have been submitted and awaiting review |
| Passed | Approved and entered into account |
| Return for supplement | The materials are incomplete and need to be supplemented before review |
| Rejected | Does not meet the identification standards and will not be counted in the time |
| Under review | Students have objections to the results and will enter review |

Merge the “pending review” and “under reconsideration” states.

## 6. Review cycle

- **On-campus service**: The review will be completed within 5 working days after the event.
- **Off-campus service**: The review will be completed within 10 working days after the materials are submitted.
- **Graduation or further education node**: Set up a centralized review window to remind students to supplement materials in advance.

## 7. Exception handling

| Abnormal situations | Handling methods |
| --- | --- |
| Incomplete materials | Return student supplementary materials |
| Duration is abnormally high | Requesting second confirmation from the organizer |
| The project does not comply with the principle of public welfare | Not recognized |
| Submit the same service repeatedly | Keep a valid record once and reject the rest |
| Supervisor unavailable | Student required to provide alternative certification or resubmit |
| Students have objections to the review results | Submit a review application for review by the Student Development Center |

## 8. Offline materials and electronic ledger management

### 8.1 Paper materials

Use the "Student Volunteer Service Record Form" as the original certification material, and keep it by number when filing to facilitate traceability and auditing.

### 8.2 Electronic ledger

A unified spreadsheet will be created by the student development center or the teacher in charge of the grade. The fields should at least include:

- Student name/student number/grade class
- Service date / service items / service organization / service length
- Review status/Reviewer/Total cumulative time/Remarks

It is recommended to create a ledger by academic year, for example:

- `2026-2027_TimeBank_Service_Log`
- `2026-2027_TimeBank_Account_Balance`
- `2026-2027_TimeBank_Redemption_Record`

### 8.3 Numbering rules

```text
TB-school year-grade-student number-serial number
```

Example:

```text
TB-2026-G06-12345-001
```

This number is used for paper filing, electronic ledger inquiry and future online system data migration.

### 8.4 Publication and Reminders



Individual cumulative progress reminders are provided to students every month or every semester, and personal privacy information is not disclosed; students who are below the stage progress requirements will be reminded one-on-one by the head teacher or the teacher in charge of the grade.

## 9. Risk control mechanism

| Risks | Performance | Controls |
| --- | --- | --- |
| False services | Submit services that did not actually occur | Require supervisor’s signature, contact information and certification materials, and sample phone calls |
| Exaggeration of duration | Actual service is less than declared | Establish a reasonable duration benchmark and double-confirm abnormal records |
| Forged signature | Forged signature of the organizer | Randomly check the contact information of the supervisor during the review stage and call for verification if necessary |
| Duplicate submission | Multiple entries for the same service | Number uniqueness verification, ledger check for duplicates |
| Project generalization | Non-public welfare activities packaged as volunteer services | Clear identification standards, final review by the Student Development Center |
| Review backlog | Form accumulation causes delays | Fixed review cycle and centralized review window |
| Loss of materials | Loss of paper or confusion of ledgers | Filing of paper numbers and regular backup of electronic ledgers |

## 10. Connection with other solutions

- The service record fields generated by manual audit are consistent with the automatic audit data structure of `03-AutoAudit.md`.
- The audited records can be used as the original data source for the certificates stored in `04-Blockchain.md`.
- After the online system is launched, manual review will be reserved as a review and evasion channel. For details, see `05-OnlineSystem.md`.
