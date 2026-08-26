# Time Bank · Automatic audit solution (AutoAudit / POV witness audit)

> This document is a sub-plan of "Time Bank Design" (`01-TimeBankDesign.md`). It is designed for **spontaneous, anytime, anywhere** volunteer service scenarios and designs an automated review mechanism with "Proof of Volunteering (POV)" as the core. The data fields continue to use `02-ManualAudit.md`; for evidence storage and uploading, see `04-Blockchain.md`; for the bearing system, see `05-OnlineSystem.md`.

## 1. Background and goals

Volunteer services organized centrally on campus can be confirmed by teachers on site, but students’ spontaneous public welfare activities (such as community cleaning, casual charity, and community mutual aid) lack teachers to accompany them, making it difficult to cover them with traditional manual signatures. The automatic audit solution needs to solve three major problems:

- **Trust Asymmetry**: How to prove that the service actually takes place and the duration is real when there is no teacher accompanying you.
- **Cheating Game**: After the service is linked to further education/excellence evaluation, there are motives such as forging witnesses, cheating on time, and backfilling dates.
- **Privacy Compliance**: Portraits involving minors and recipients must be desensitized during the collection process.

Goal: Without destroying the original intention of spontaneous public welfare, through "lightweight front-end + end-side desensitization + witness weak verification + statistical risk control + multi-modal AI preliminary review" to achieve high authenticity, low labor cost, and auditable automatic review closed loop.

## 2. Core concept: POV witness proof

POV refers to the multi-party endorsement of the authenticity of a spontaneous charity act by **passerby witnesses/recipients/peer team members/guardians** at the service site. It combines device metadata (GPS, timestamps) and AI scene recognition to form a credibility score and drive automatic or semi-automatic review.

The credibility of a POV is weighted by four types of evidence:

- **Device Evidence**: GPS coordinates, precise timestamp, device fingerprint.
- **Witness evidence**: The witness’s real name (WeChat mobile number) is confirmed.
- **Content Evidence**: Scene and behavior tags of photos/videos recognized by multi-modal AI.
- **Social Evidence**: Team members’ mutual certification, guardian’s endorsement, and peer publicity and supervision.

### 2.1 Quadruple witness model (weighted scoring, not “one without the other”)

Since there are no teachers present at the spontaneous public welfare site, this plan distributes "trust" to a social third party, and the four dimensions of testimony jointly constitute credibility. The four **correspond to the above four types of evidence**, and are implemented as operable collection actions:

| Witness dimension | Corresponding evidence | Specific collection actions | Main functions |
| --- | --- | --- | --- |
| 1. Witness of space and time | Equipment evidence | After arriving at the public welfare location, "check in" in the system and automatically record real-time GPS and unmodifiable timestamp | Prove that "you have been to a specific place at a specific time" |
| 2. Witness the activity process | Content evidence | Upload 2-3 on-site photos or a short video of about 10 seconds, including the person, the charity scene, and markers of the time (such as the day's newspaper/banner with the date) | Prove that "the charity is indeed being done" |
| 3. On-site third-party witness | Witness evidence | On-site person in charge/community staff/beneficiary scans the QR code and is confirmed by WeChat real-name "two-second closed loop" (see §5 for details) | Introducing social third-party endorsement |
| 4. Peer mutual evaluation and witness | Social evidence | When teaming up for charity, group members confirm each other's participation and implement a "joint and several responsibility system" (see §7.1, §7.2 for details) | Use peer pressure to prevent cheating |

> **Key design principle: The four-part attestation model uses weighted scoring; no single component is universally mandatory.**
>
> - In high-guarantee scenarios such as **fixed bases**, four-layer witnessing can be provided at the same time, with the highest confidence and the lowest automatic pass threshold.
> - At the scene of **spontaneous and sporadic charity** (such as helping the neighbor's grandma carry her luggage, picking up garbage), it is likely that there will be **no third-party witnesses**. At this time, there should not be a single vote to veto, but a weighted score of "GPS + timestamp + multimedia content + guardian endorsement", in conjunction with the **daily time coin hard limit + statistical risk control + publicity/spot checks** (see §5.3, §8, §14, §15 for details).
> - Forcing "four elements are indispensable" will stifle the original intention of spontaneous public welfare, and is contrary to the overall strategy of this plan to "shift from ex-ante blocking to ex-post risk control".
>
> Regarding the coordination between "self" and "desensitization on the terminal side" when the photo needs to be included: witness the **original picture** on-site, and code the person's face on the terminal side before uploading (schedule isolation in plan A, see §4.3 for details). There is no conflict between the two.

### 2.2 Terminology explanation: Why use Proof of Volunteering instead of Proof of Witness

This program is named **Proof of Volunteering (POV)** instead of "Proof of Witness" for three reasons:

- **Correct naming level**: POV describes "**what to prove**" - the goal to prove is "this volunteer service really happened"; and "witness" is only one of the means of "**how to prove**". Treating means as the name of a plan narrows the scope.
- **Consistent with the multi-source weighted design**: POV credibility combines four weighted evidence dimensions: device, witness, content, and social corroboration. A witness is only one component; naming the system Proof of Witness would contradict this design.
- **Avoid abbreviation conflict**: The abbreviation PoW of Proof of Witness seriously conflicts with the blockchain "Proof of Work", and it is easy to be confused in this project that contains blockchain certificates (see `04-Blockchain.md`); POV is unambiguous.

Therefore, **POV is used as the name of the program and "Witness Proof" is used as its core mechanism**, which is more intuitive to non-technical audiences such as teachers and parents.

## 3. Participants and Responsibilities

| Role | Responsibilities |
| --- | --- |
| Students participating in public welfare | Take photos and check in on site, display the verification code, and fill in the service information truthfully |
| Passer-by witnesses/recipients | Scan the QR code on WeChat, check the scene, and confirm the authenticity of the testimony with one click |
| Peer team members | Support each other when working together for charity (GPS fence + mutual signature) |
| Guardians (parents) | Sign online to endorse students’ spontaneous charity on the day and bear joint credit |
| Teacher on duty/head teacher | One-click review and spot check of records that have passed the AI preliminary review |
| Student Development Center | Formulate rules, handle disputes, and organize blind evaluations of high-value certificates |

## 4. POV complete review process

### 4.1 Timing process

```mermaid
flowchart TD
A[Step 1: Take a photo of the original picture on site<br/>Grab GPS + timestamp] --> B[Step 2: Weak verification by passersby scanning the QR code<br/>Verify the original picture + WeChat real-name confirmation]
B --> C[Step 3 End-side edge AI<br/>Local Gaussian blur/cartoon mask]
C --> D[Step 4: Upload the desensitized image + calculate SHA-256<br/>The original image memory will be destroyed without leaving the disk]
D --> E [Step 5 Multi-modal AI scenario compliance preliminary review]
E --> F{AI risk control score}
F -- High reliability --> G [Automatically pass <br/> into the teacher spot check pool]
F -- Medium confidence --> H[Transfer to teacher for manual second review]
F -- Low confidence/abnormal --> I[Interception warning<br/>Transfer to manual audit]
G --> J [Mutual certification between team members + endorsement by guardian]
H --> J
J --> K [Approved and credited with time coins]
K --> L[Key metadata storage certificate on the chain<br/>See 04-Blockchain.md for details]
```

### 4.2 Detailed explanation of steps

- **Step 1 · On-site recording**: In the third stage, after the POV WeChat applet is enabled, students take original pictures of team members, environment, and witnesses at the charity site, and the system automatically captures the underlying GPS coordinates and precise timestamps. The complete web system in the second stage does not rely on this capability and still uses web material submission and manual review to complete the business closed loop.
- **Step 2 · Witness weak verification**: The student shows the verification QR code, the witness scans the code with WeChat, and only needs to click "Confirm the Witness is Authentic" through the official real-name interface, and the system does not need to obtain the real-name mobile phone number bound to WeChat; the witness displays the **uncoded original image** to verify that the scene is consistent with the student.
- **Step 3 · End-side desensitization**: The moment the witness confirms, the mini program locally activates the edge model (WASM/JS), and performs Gaussian blur or cartoon mask on the faces of all minors and recipients within 0.1 seconds.
- **Step 4 · Upload and destroy**: Only upload **desensitized images** to cloud storage, and calculate the SHA-256 hash; the original images are destroyed in the phone's memory and will never be dropped to disk or uploaded.
- **Step 5 · AI preliminary review**: The back-end multi-modal model identifies scene/object labels, compares the consistency of the application content with the picture, and outputs a credibility score.
- **Step 6 · Multi-party evidence**: Mutual verification by team members + online signature and endorsement by guardians.
- **Step 7 · Manual spot check/second review**: The teacher conducts spot checks on high-credibility records and conducts a second review on medium-credibility records.
- **Step 8 · Account entry and evidence storage**: After passing, the time coin is converted, and the key metadata hash is uploaded to the chain for evidence storage.

### 4.3 Conflicts and solutions between desensitization and witnessing

**Core Contradiction**: If the photo is **taken and coded** on a mobile phone, when the witness scans the code, he or she will see a full screen mosaic and cannot check "whether the children in front of them and this public welfare place are in the photo." The witness will be meaningless. In order to simultaneously satisfy **privacy protection of minors** and **authenticity of witness**, two closed-loop solutions are provided.

#### Plan A·Sequential isolation method (witness first, then upload with desensitization, recommended)

Logic: During on-site witnessing, the photo should remain the **original image**. After the witness confirms and signs, the system will code it in the local background, and then upload and upload it to the chain.

- Step 1: Take a clear **original photo** of the team members, environment, and witnesses on site.
- Step 2: The witness scans the QR code, and the **uncoded original image** is displayed on the mobile phone screen. After checking "the person is right, the place is right, and the time is right", click to sign and confirm.
- Step 3: At the moment of confirmation, the local code (WASM/JS) codes the faces of all minors/elderly people within 0.1 seconds.
- Step 4: Only upload the **desensitized image** and calculate the hash and upload it to the chain. The original image will be destroyed in the phone's memory and will never be uploaded.
- Feasibility: What witnesses witness is "the live scene and the original picture that was just taken", while what is recorded on the Internet/chain is "a social asset that has been desensitized, cannot be traced to real people, but is tamper-proof."

#### Option B · Partial visibility method (face coding, environment retention)

Logic: Because the witness is already standing in front of the student, the witness does not need to inspect the student’s face; they only need to confirm that the **background and activity** match the public-interest service location.



- Call local lightweight models (such as BlazeFace) instantly when taking photos, and **only for minors’ faces** real-time coding or cartoon masks (little sun/little red flower).
- Witnesses can witness when they see the photo of "a small red flower on the face, but with a clear background of the nursing home" and check the clothing, body shape and background with the real person in front of them.
- The "partial coding picture" is directly uploaded to the chain, and privacy protection and witness certification are unified.

#### Plan comparison and recommendation

| Dimensions | Plan A timing isolation | Plan B partially visible |
| --- | --- | --- |
| Witness information completeness | High (witness the original picture) | Medium (only environment + body shape) |
| Privacy strength | High (final image fully desensitized) | Medium (environment visible) |
| Implementation complexity | Medium (requires timing control) | Low (photographing and coding) |
| Risk of accidental touch | The original image is temporarily stored in the memory before confirmation | Almost no original image is exposed |
| Applicable scenarios | Forced witnessing is required (witness by strangers) | Witnesses are already on site (team members/parents/base administrators) |

> **Recommendation**: **Option A (sequential isolation) is the main option** for strong authenticity scenarios witnessed by strangers; **Option B (partially visible) is the supplement** for scenarios where witnesses are already present and privacy is a priority. Both require that **the original image must never be uploaded**.

## 5. Witness verification and identity authenticity

### 5.1 Weak verification "Two seconds closed loop"

In order to protect the enthusiasm of witnesses, the verification by scanning the QR code must be "closed in two seconds":

```text
Scan the QR code → WeChat automatically authorizes the mobile phone number → Click "Agree to Witness" → End
```

- **Does not force** witnesses to download the app, register an account or enter a verification code.
- The witness will display the original image or partial coding image according to plan A/B for on-site verification.
- A deterrent text is marked at the bottom of the page: "This certificate has been stored on the blockchain to prevent fraud. The system will randomly call witnesses on about 5% of the records" to dissuade acquaintances/parents from assisting in cheating at a very low cost.
- The system only retains the **encrypted hash** of the witness's mobile phone number and WeChat UnionID (see Section 9 for details), and does not store the complete number in text or display it externally.

### 5.2 Comparison and recommendation of four paths of witness identity authenticity (anti-Sybil)

**Question**: If the "witness" is forged by a student using a trumpet or does not exist at all, the entire chain of evidence will collapse. Under the premise of domestic compliance, there are four paths from shallow to deep.

| Path | Practice | Credibility | Development cost | Friendliness towards voluntary public welfare |
| --- | --- | --- | --- | --- |
| 1. WeChat real-name ecological leverage (weak verification) | Witnesses scan the QR code to authorize the WeChat real-name mobile phone number/UnionID (encrypted on the chain) | Medium to high | Extremely low (zero development) | High (does not damage the public welfare) |
| 2. Pre-made public welfare base whitelist | Sign a contract with the community/nursing home/library in advance, and only the whitelist administrator can scan the code to confirm | Highest | Medium (requires contract operation) | Low (limited locations) |
| 3. Witness credit score risk control | Use big data behavior analysis to dynamically score scattered witnesses and blacklist them | Medium to high | Medium (requires risk control model) | High |
| 4. Multiple endorsements from the circle of acquaintances | Mutual certification among team members + second signature of the guardian + joint credit | Medium to high | Low | High |

> **Recommended combination**:
> - **Daily fixed-point public welfare** → Take **Path 2 (whitelist base)**, the witness is the base registration administrator, and solve the problem of "confirming the existence of the person" from the source.
> - **Spontaneous scattered public welfare** → **Path 1 + 3 + 4** Overlay: WeChat real-name weak verification by passers-by + low upper limit for single time coin + AI frequency/time and space risk control + endorsement from circle of acquaintances.
>
> Note: If public-interest service is rigidly limited to whitelisted sites, it degenerates into “checking off required hours” and undermines spontaneous acts such as picking up litter or helping an elderly neighbor carry luggage. The overall strategy therefore shifts from **blocking everything in advance** to **post-event risk control**.

### 5.3 Fixed-point base mode vs open spontaneous mode

| Mode | Authenticity Guarantee | Advantages | Disadvantages | Applicability |
| --- | --- | --- | --- | --- |
| Designated base (pre-credit granted) | Whitelist administrator scans the QR code | Highest credibility, easy to audit | Limited venues, inhibiting spontaneity | Fixed weekend charity, long-term cooperation projects |
| Open and spontaneous (post-event risk control) | WeChat real-name verification + mutual attestation + AI risk control + publication | Preserves spontaneity and covers informal public-interest service | Depends on risk controls and spot checks | Picking up litter, community mutual aid, and spontaneous public-interest service |


> **Recommendation**: Two modes **in parallel**, automatically adapting the audit intensity according to the service source; the open mode cooperates with "low quota + soft incentives" (see Section 14) to eliminate the motivation for cheating from the source.

## 6. Operations that public welfare participants need to do

- Make sure positioning and camera permissions are turned on before checking in.
- Take real photos/short videos on site, including identifiable public welfare scenes and behaviors.
- Invite on-site witnesses to scan the QR code to confirm (if any).
- Fill in the service type, brief description, and duration.
- When teaming up, ensure that group members check in at the same time (triggering GPS fence mutual authentication).
- Ask your guardian to endorse online for confirmation after returning home.

### Specific operation cases

- **Case 1 · Designated nursing home (white list)**: Xiao Ming went to the contracted nursing home to accompany the elderly for 2 hours on weekends. The white list administrator of the nursing home scanned the code to confirm → high credibility, automatically passed and entered the random inspection pool.
- **Case 2 · Open roadside public welfare**: Xiaohong and Xiaogang picked up plastic bottles in the park for 1 hour (scheduled isolation and photography in plan A). A passerby "Mr. Zhang" scanned the WeChat code for weak verification. The GPS of the two people coincided with each other. After returning home, their parents endorsed → Medium to high credibility, entered the random inspection pool; included in time coins according to the upper limit of the spontaneous charity day.
- **Case 3 · Helping the neighbor’s grandma carry her luggage**: Single person charity, no passers-by to witness, only parent endorsement + GPS/time → Trustworthy, transferred to the teacher for second review.

## 7. Mutual certification and multi-party endorsement mechanism

### 7.1 Mutual verification among team members

When 2-3 students work together for charity, the system compares the GPS fences and time windows of multiple mobile phones and automatically establishes a mutual verification relationship, making it significantly more difficult for a single person to fake.

### 7.2 Guardian’s joint endorsement

Students’ charity on the day must be signed and endorsed by their parents on WeChat. Once the fraud is found out afterwards, the "joint credit scores" of the entire group of students and all endorsing parents will be cleared and reported, significantly increasing the psychological cost of cheating.

### 7.3 Social dynamic publicity wall

The dynamics that pass the preliminary review will be publicized in the class or school applet. The **publicity period shall be no less than 3 days** and a built-in "Document and Question" button will be provided for students to report abnormalities.

- **Public content**: Only display the desensitized picture, student name (can be desensitized to "Xiao Ming"), witness's surname and mobile phone number suffix (such as "Mr. Zhang 135\*\*\*\*8888"), service type and duration.
- **Report Closed Loop**: Any student who clicks "Question" will automatically be transferred to the teacher for manual review. The questioner's anonymity is protected and the whistleblower's information is not displayed.
- **Core logic**: Students know one another’s whereabouts better than anyone else, so peer publication strongly deters false check-ins—a cheating student’s classmates will know that “they were not there that day.” The three-day publication period and telephone spot checks (see §14.1) provide two layers of protection: public oversight and sampled verification.


## 8. Review standards and credibility scoring

### 8.1 Automatically pass/convert to manual/interception rules

| Scoring interval | Processing | Trigger condition example |
| --- | --- | --- |
| High reliability | Automatically passed + spot check | GPS/time is normal, AI scene matching, witnesses’ real names, and team members’ mutual certification is complete |
| China Credibility | Second review for teacher transfer | Lack of witnesses, single person charity, partial matching of AI tags |
| Low confidence | Interception warning | Time and space conflict, scene inconsistency, suspected remake/P-picture, witness anomaly |

### 8.2 Multimodal AI review dimensions

> For technical details, access solutions and cost estimates of each AI module, please see `07-AI.md`.

- **Scenario consistency**: Declared "garbage classification promotion" but identified as an indoor Internet cafe/playground with no cleaning tools → intercepted.
- **EXIF and Consistency**: Whether the shooting time and aperture match the punch-in time.
- **Remake/Tampering Detection**: Frequency domain analysis identifies whether the screen has been re-reproduced or P-pictured.

### 8.3 Statistical anomaly risk control (anti-money laundering/anti-brushing model)

Under the temptation of interests, parents' methods of forging witnesses will escalate, but the laws of statistics cannot be forged. Drawing on the financial "anti-money laundering/anti-shopping" model (for detailed rules and comprehensive scoring formula, see Section 5 of `07-AI.md`):

- **Metcalf Dispersion Filter**: For truly spontaneous public welfare, the time, location, witness mobile phone numbers, and classmates should be **highly discrete and random**. If the mobile phone numbers of witnesses for 15 out of 20 public welfare activities of a student belong to the same number range, or these witnesses are highly related in the WeChat ecosystem → it will be determined as a "family cheating chain", and the right will be directly reduced or the qualification for evaluation will be cancelled.
- **Frequency Fence**: The same witness mobile phone number frequently signs multiple unrelated students across regions in a short period of time, or signs for a specific student on a fixed basis → it will be judged as a "professional witness" and the link will be broken.
- **Geo-fencing**: The coordinate difference between the same student's two check-ins exceeds the traffic limit (such as 30 kilometers apart within half an hour) → Orange warning is handed over to manual work.
- **Season/Environment Consistency**: Photo vegetation is shown as summer, but GPS+timestamps are winter → Multi-modal AI direct interception.

### 8.4 Witness credit score mechanism

Introduce dynamic credit scores for passerby witnesses who are scattered (cannot be pre-bound to the whitelist):

- The witness’s mobile phone number **appears** for the first time to sign for a certain student → the initial review can be passed.
- This account frequently signed for multiple unrelated students in a short period of time, or there were time and space conflicts → It was judged as a "false identity/brush", and was **blacklisted** and the time coins witnessed by the relevant students were deducted.
- Credit scores are linked to Section 7 "Joint Credit": Once the cheating is confirmed, the credit scores of the students, endorsing parents, and classmates' WeChat accounts will be reduced simultaneously.

## 9. Witness metadata storage design

### 9.1 Storage Principles

- **Minimization**: Only the fields required for auditing and evidence storage are left, and all sensitive information is hashed.
- **Desensitization priority**: Only the desensitized version of the picture exists; the original picture will not be saved.
- **Hiered Storage**: business inventory readable metadata, only hash fingerprints are stored on the chain (see `04-Blockchain.md`).

### 9.2 Metadata fields

| Field | Description | Sensitivity level |
| --- | --- | --- |
| Record number | POV record unique ID | Low |
| Student ID | Link Student Account | Medium |
| Service time window | Start/end timestamp | Low |
| GPS coordinates | Punch in latitude and longitude (can be blurred) | Medium |
| Device fingerprint hash | Device unique identifier hash | Medium |
| Masked image URL | Cloud storage address (only masked version) | Medium |
| Desensitization map Hash | Desensitization map SHA-256 | Low |
| Witness mobile phone number Hash | Real-name mobile phone number salted hash | High (hash only) |
| Witness UnionID Hash | WeChat unique identifier encrypted hash, anti-Sybil duplication check | High (hash only) |
| Witness Type | Passerby/Recipient/Team Member/Guardian/Base Administrator | Low |
| Mutual authentication ID list of group members | Associated group member records | Medium |
| Guardian Endorsement Hash | Parent Confirmation Signature Hash | High (hash only) |
| AI Rating and Labeling | Confidence Score and Scenario Labeling | Low |
| Review teacher’s signature | Second review/spot check teacher’s digital signature | Medium |
| Review status | Approved/second review/intercepted/rejected | Low |
| On-chain transaction hash | Deposit TxHash (if enabled) | Low |

### 9.3 Retention and destruction

- Original image: **not stored** (destroyed on the client side).
- Desensitization pictures: retained by academic year, and will be cleaned regularly according to the privacy policy after graduation and archiving.
- Mobile numbers/endorsements: Store only salted hashes, allowing duplicate comparison without enabling reverse identification.


## 10. Analysis of pros and cons of implementation path

### 10.1 End-to-side desensitization solution

| Path | Advantages | Disadvantages |
| --- | --- | --- |
| End-side edge AI (WASM, such as MediaPipe/BlazeFace, <1MB) | The original image is not exported locally, with optimal privacy and does not consume server computing power | The performance of low-end machines fluctuates and compatibility adaptation is required |
| Cloud desensitization | Simple implementation, stable effect | Original image needs to be uploaded, high privacy risk, high compliance pressure |

**Recommended: Device-side desensitization**, the cloud is only used as a cover for downgrading unsupported models (desensitization and burning when uploaded).

### 10.2 Witness verification scheme

| Path | Advantages | Disadvantages |
| --- | --- | --- |
| WeChat scan code + weak verification of real-name mobile phone number | Two-second closed loop, zero interruption, real-name trustworthiness | Rely on WeChat ecosystem, compliance authorization required |
| Witness SMS verification code | Does not rely on WeChat | Large interruption, high abandonment rate |
| Witness App Strong Verification | The Most Complete Information | Passers-by almost don’t know how to install it, so it’s not feasible |

**Recommendation: WeChat scan code weak verification** is the main method, supplemented by the teacher’s second review.

### 10.3 Level of audit automation

| Path | Advantages | Disadvantages |
| --- | --- | --- |
| Fully automatic AI review | Lowest labor cost | Risk of misjudgment, insufficient evaluation of minors |
| AI preliminary review + teacher spot check/second review (recommended) | Balance cost and fairness, traceability of responsibilities | Still requires a certain amount of manual investment |
| Purely manual | The most reliable | Unable to cover the scale of spontaneous public welfare |

**Recommendation: AI preliminary review + manual spot check/second review** semi-automatic mode.

### 10.4 Overall Recommended Approach



> **End-side desensitization + WeChat code scanning weak verification + Multi-modal AI preliminary review + Team member mutual certification/guardian endorsement + Teacher spot check second review + Key metadata hashing on the chain**.
> High-value certificates (related to higher education) additionally trigger "cross-school blind evaluation" (see Section 11).

## 11. Cross-school blind evaluation of high-value certificates

Blind review is triggered when a student applies for a “comprehensive quality honor certificate” related to further education:

- Automatically export the student’s full-year public-interest service history and de-identified materials, removing identity information such as name, class, gender, and school name.




- Randomly distributed to blind judges composed of representatives of parent committees of **other schools** or judges from off-campus volunteer associations.
- The reviewers voted secretly on sustainability, social value, and realism; since the ownership of the materials cannot be known in advance, the rent-seeking space is locked by technology.

## 12. Fault tolerance and cover mechanism

- **Network disconnection fault tolerance**: Supports local caching when there is no signal in mountainous areas. First lock the encrypted photo album with GPS, and asynchronously upload and re-sign after the network is restored.
- **Recipients without smartphones**: Switch to the "on-site landmark shooting + head teacher/parent home visit recording confirmation" special approval channel.
- **AI misjudgment appeal**: Students can submit an appeal on the interception result and transfer it to manual audit and review.

## 13. Risk control mechanism

| Risk | Control |
| --- | --- |
| Forgery of witnesses/scoring of points | WeChat real name + frequency/time and space fence fuse professional witness |
| Remake, P-picture | EXIF verification + frequency domain analysis + multi-modal scene comparison |
| Backfill date | Timestamp iron seal + key metadata on the chain (see `04-Blockchain.md`) |
| Parents involved in cheating | Clearance of guardians’ joint credit + Cross-school blind evaluation of high-value materials |
| Privacy leakage | Device-side desensitization, original images not saved, sensitive fields only hashed |
| AI misjudgment | Semi-automatic mode + appeal review |
| Witnesses are harassed | Only the hash is retained, the complete number is not disclosed, and the loop is closed in two seconds |

## 14. Mechanism: 10% other than technology

Technical means (AI review, statistical risk control, and blockchain evidence storage) can identify and intercept most fraud, but they cannot cover every edge case. Carefully coordinated group fraud and new methods outside current AI coverage will always exist. **Technology can address only 90% of the problem; institutional safeguards must cover the remaining 10%.**

This safeguard does not rely on algorithms; it relies on **human participation and social oversight**. Its core is to keep potential cheaters uncertain about whether they will be selected for verification or reported by classmates.

### 14.1 Random telephone spot check (5% sampling verification)

**Mechanism**: The system automatically randomly selects **5%** entries from each batch of approved records, and school volunteers or student development center staff call the **witness’ mobile phone number** for manual verification.

**Verification Skills** (Example, 10–20 seconds to complete):
> "Hello, you scanned the QR code to witness [student's pseudonym]'s charity event at [location] last week. Is it true? Thank you for your cooperation. Goodbye."

**Key Design Details**:

| Dimensions | Design | Justification |
| --- | --- | --- |
| Spot-check ratio | 5% (configurable) | Controllable cost (approximately 750 calls per school year), deterrent effect significantly higher than the ratio itself |
| Spot check time | Randomly triggered within 3–14 days after witnessing | Too early (in the day) to disturb the witness; Too late to blur the memory; Best within 3–14 days |
| Execution subject | School volunteers/Parents Committee representatives/Student Development Center | Zero outsourcing cost, strong sense of participation of the Parent Committee, and higher deterrent power than machine voice |
| Result processing | Witness denial → Rejection of the corresponding record + clearing of associated credit | The penalty for a single verification failure covers multiple records of the student |
| Deterrence notice | Display “5% random telephone verification” on the witness confirmation page | Creates a psychological deterrent before a fraudulent submission is made |


**Why 5% is Enough**: Students have no way of predicting whether they will be selected. For a student who plans to commit fraud, the 5% probability of being exposed on the spot × the cost of clearing credit, the actual expected cost is much higher than the value of the time coin obtained - this is a typical principle of deterrence economics.

### 14.2 Campus Publicity Wall (3-day publicity period)

> See §7.3 Social Dynamic Publicity Wall for details.

**Additional note**: The publication wall works because it reverses information asymmetry, not because of technology. AI does not know whether “Xiao Ming was actually in the park that day,” but Xiao Ming’s classmates do. A three-day publication period gives classmates enough time to identify and anonymously report anomalies at no personal cost.



**Complementary relationship between the two mechanisms**:

| Mechanism | Coverage Scenario | Cost | Core Deterrence |
| --- | --- | --- | --- |
| Random telephone spot checks | Whether the witness himself/herself is present | Low (5% × 20 seconds/call) | Uncertainty and fear (may be selected) |
| Campus Publicity Wall | Whether students actually participated in the activity | Very low (automatic by the system) | Social pressure from acquaintances (students know each other best) |

The two coverages complement each other: phone verification verifies witnesses, and the publicity wall verifies the student's own behavior, which together constitute a **social trust layer** that cannot be replaced by technical means.

## 15. Time coin incentive economics (low limit + soft incentive)

The root reason why students forge witnesses to gain points is that "time coins are too valuable." Designing the incentives for voluntary public welfare to be "just right" can eliminate the motivation for cheating from the source.

### 15.1 Quota ceiling

- There is a **hard limit on daily time coins** for spontaneous and sporadic public welfare (picking up garbage, helping the elderly, etc.) (example: maximum 2 coins per day).
- Exchange anchoring makes cheating unprofitable (example: changing a piece of rubber costs 10 coins, while the daily spontaneous charity cap is 2 coins).

### 15.2 Diversion of incentives (mainly spiritual, supplemented by material)

| Service Type | Time Coin (Material) | Spiritual Incentive | Reason |
| --- | --- | --- | --- |
| Sporadic public welfare | Low (daily cap) | High: medals, titles, class rankings | Without huge material benefits, it is not worth the effort to forge witnesses |
| Designated base/project with preliminary review | Middle and high school | Medium | There is a whitelist or preliminary review by teachers, and the threshold for cheating is high |
| Reading and other high-investment projects | High | Medium | There is a preliminary review of the reading experience, and the time and mental cost is high, and the threshold for cheating is extremely high |

> **Recommendation**: Use "low quota + high honor" for voluntary public welfare to protect the pure sense of honor; projects with high thresholds/can be initially reviewed will be given higher time coins. This mechanism and the risk control in Section 5.2/5.3 form a double insurance of "source governance + process risk control". This section is the implementation standard for the review side, which is consistent with the top-level incentive system of `01-TimeBankDesign.md`.

## 16. End-to-end case demonstration

Take "Xiaohong and Xiaogang picking up plastic bottles in the park" as an example to connect the whole process:

1. **Photography**: Two people complete the cleanup. According to plan A, they take the original picture of the two parties, garbage bags, park environment and passers-by. The system captures the GPS + timestamp.
2. **Witness**: Mr. Zhang, a passerby, scans the QR code on WeChat, and the original uncensored image is displayed on the screen. If it is correct, click "Agree and authorize mobile phone number" (2 seconds).
3. **Desensitization**: Two people's faces are coded locally at the moment of confirmation, only the desensitization image is uploaded and SHA-256 is calculated, and the original image memory is destroyed.
4. **Mutual Authentication**: If the GPS fences of two people’s mobile phones overlap and the time window is the same, mutual authentication among the team members will be automatically established.
5. **AI preliminary review**: Multi-modal recognition "park/garbage bag/cleanup" label matching, the season is consistent with GPS, Mr. Zhang's mobile phone number appears for the first time and is discrete → high confidence.
6. **Parent Endorsement**: Both parents will sign on WeChat for confirmation that night.
7. **Accounting**: After passing the review, time coins will be included according to the upper limit of the spontaneous charity day + the "Environmental Protection Little Guardian" medal will be issued.
8. **Evidence storage**: Record number, desensitized image hash, witness mobile phone number/UnionID hash, parent endorsement hash, and timestamp batch uploading to the chain (see `04-Blockchain.md`).
9. **Public Notice**: The dynamic will be pushed to the class dynamic wall and will be published for 3 days. Students can question it with one click; a "5% random phone verification" prompt will be displayed at the bottom of the public notice page.
10. **Spot check**: The system has a 5% probability of randomly selecting → Volunteers from the Family Committee will call Mr. Zhang’s mobile phone within 3-14 days to verify (about 20 seconds) and confirm that it is true.

## 17. Connection with other solutions

- The data fields are consistent with `02-ManualAudit.md`, and manual review is used as a channel for verification and dispute resolution.
- After passing the review, the key metadata (hash, time, signature) is uploaded to the chain and stored as a certificate according to `04-Blockchain.md`.
- All capabilities are carried by the online system described in `05-OnlineSystem.md`.
- For details on the technical implementation of client-side desensitization, multi-modal AI content review, image authenticity detection, statistical risk control engine and witness credit scoring, please see `07-AI.md`.
