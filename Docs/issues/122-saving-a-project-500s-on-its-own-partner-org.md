# 122 — Saving a project 500s on its own partner organization

**Status:** **Fixed and deployed** 2026-09-20, same day. Found while verifying
issue 121, not reported — the error it produced was the generic
`INTERNAL_ERROR`, which is exactly what that work made legible.

## 1. What happens

Open any project with a partner organization, change anything or nothing, press
Save. `500`.

```
Duplicate entry 'proj_63zd…-org_f7a2…-PARTNER'
  for key 'project_organization.uk_project_organization_role'
  [insert into project_organization (organization_id,project_id,role,sort_order,id) …]
  at ProjectServiceImpl.updateDraft(ProjectServiceImpl.java:238)
```

`updateDraft` maintains the `project_organization` join row by deleting the
PARTNER row and saving a fresh one:

```java
projectOrganizationRepository.deleteByProjectIdAndRole(project.getId(), PARTNER);
projectOrganizationRepository.save(new ProjectOrganization(…));
```

**Hibernate orders inserts before deletes at flush**, regardless of the order
they were requested in. When the organization is unchanged, the new row has the
same `(project, organization, role)` as the one still sitting in the table, so
the insert goes first and hits the unique key.

It only misses when the partner actually *changes* — then the two rows differ
and there is no clash. So the bug fires on the ordinary edit (fix a location,
leave the partner alone) and hides during the unusual one.

## 2. This also broke Copy to draft

Issue 119 had Copy to draft create the draft and then open it for editing,
precisely so the copied dates get looked at before publishing. A copy inherits
the source's partner organization, so pressing Save in that dialog was the
failing path every time. The feature shipped a day earlier with its last step
broken.

## 3. The fix

One line: flush the delete before the insert.

```java
projectOrganizationRepository.deleteByProjectIdAndRole(project.getId(), PARTNER);
projectOrganizationRepository.flush();
projectOrganizationRepository.save(new ProjectOrganization(…));
```

**Considered and rejected: skipping the rewrite when the organization is
unchanged.** It looks like the more precise fix and it is not a fix at all — it
was tried first, and the red-check below shows the flush alone is sufficient and
the guard alone is not. Keeping both would have left a branch that reads as
load-bearing while the real repair sat next to it unexplained.

## 4. Verification

A new integration test saves a project twice with the same partner id — which is
exactly what the edit dialog sends — and then changes the partner, so the fix
cannot be "stop writing the row".

Red-checked by removing each half separately, which is what settled §3:

| Change | Result |
| :--- | :--- |
| flush present, unchanged-guard removed | **passes** |
| flush removed | **fails**: `Duplicate entry … for key 'project_organization.uk_project_organization_role'` |

And on the deployed server, the same Save that produced the 500 twenty minutes
earlier:

```
editing: Community Reading Companions （副本） | partner set: true
PATCH:   200
toast:   项目「Community Reading Companions （副本）」已更新
dialog closed: true
```

## 5. What it must not do

- **Must not skip writing the join row.** §3 — that hides the bug rather than
  fixing it, and silently stops maintaining the row.
- **Must not rely on request order at flush.** JPA does not promise it. §1.
- **Must not leave the test asserting only the unchanged case.** Changing the
  partner has to keep working, or "fixed" means "no longer writes".
