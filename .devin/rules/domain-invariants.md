---
description: Domain invariants and business rules that must never be violated
trigger: always_on
---

# Domain Invariants

These are load-bearing rules from the institutional design and API contract. Violating any of them is a defect.

## Ledger & Accounts

- **Balances change only through `AccountService`** — no direct SQL update, no balance-setter
- **Every balance change is an immutable `AccountTransaction`** — corrections/reversals append opposite-signed entries
- **Crediting calculation uses integers only** — minutes for duration, centi-coins for amounts, no floats/doubles/BigDecimal
- **Rounding happens at crediting only** — nearest 30 minutes, per the versioned rule

## The Approval Transaction (5 effects, atomic)

Review approval must produce exactly these in ONE transaction, or none:
1. Review decision event (append-only)
2. Service record status update
3. Account transaction (ledger credit)
4. Student progress update
5. Notification

**Never split this.** A partially-applied approval is the failure mode this entire design prevents.

## Status Machines

- **Backend owns all status transitions** — frontend displays state, never derives it
- **Use action endpoints** (`/submit`, `/publish`, `/close`), never `PATCH {status}`
- Defined for: `Project`, `EventProposal`, `Application` (Enrollment), `ServiceRecord`, `Case`

## Event Proposal → Project Pipeline

- A proposal is NOT a project until approved
- Approval must **atomically** generate the project draft AND backfill `approvedProjectId`
- Never create the project first and link separately

## Reviews & Decisions

- **Non-overwritable** — changing a decision appends a new event, never mutates the old one
- Review history is complete and readable

## Data Privacy

- Guardian/contact fields excluded from list responses
- Evidence file access requires permission check + short-lived URL + audit log for cross-student access
- Never log tokens, session IDs, evidence URLs, or personal-data bodies

## Time Coins — Not Currency

- Never call them "tokens", "currency", or use financial vocabulary (代币/发币/挖矿/提现/钱包)
- No transfer between students, no cash-out, no tradeable semantics
- Phase 3/4 features are feature-flagged, show "Not yet available", never half-built

## Proposal Organization Input

- Choose an existing partner organization by opaque `organizationId`, or provide `newOrganizationName`
- The backend atomically resolves/creates the organization during proposal creation
- Students do not need `organizations.manage` merely to propose a new organization

## Current Project Lifecycle

- `CLOSED` is time-derived — registration window elapsed
- Managers use `POST /projects/{id}/cancel` with a required reason to cancel/archive unwanted projects, including drafts
- Normal archive from `COMPLETED` is gated until every confirmed student's service record is reviewed
- A registration-open project with zero confirmed registrants auto-archives when registration closes

## Service-Record Sources

- New records are `PROJECT` or `SELF`
- `PROJECT` records require confirmed registration; snapshot project-derived org/category/location/supervisor (immutable)
- `SELF` records need explicit supervisor kind; external-manual requires name and contact
- Legacy `EXTERNAL` source is resubmit-only for historical records
- Respect record-number sequencing and overnight (`endsNextDay`) behavior

## Roles, Users, and Scope

- Each user has exactly one `roleCode` and therefore one workspace
- Roles and permissions are data-driven; no fixed frontend role lists or `/workspace` picker
- Every non-`student` role has school-wide data scope; grade assignments are organizational metadata, not visibility boundary
- `student`, `admin`, `sysadmin` are fully protected roles; `sysadmin` hidden from standard UI
- Custom roles cannot receive student-submission permissions (`eventProposals.create`, `applications.create`, `serviceRecords.create`)
- Role templates are permission presets; only `admin`/`sysadmin` may manage them

## IDs

- Opaque server-generated strings — never parse or derive meaning from format
- Generated via `OpaqueIds` (32-char)
