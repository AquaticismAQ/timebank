# 106 — The Users tab shows email instead of the username

**Status:** Fixed, deployed and live-verified (§4).
**Raised:** 2026-09-19 by the user:

> For all users, the email is optional. The 'Users' tab in system admin page
> needs to show username — just replace the email column with it. Email can
> still be seen in the edit dialog. And the search feature should support both
> name searching and username searching.

## 1. Why the current column is the wrong one

Email is **optional** on every account. `CreateUserRequest.email` carries
`@Email` and deliberately no `@NotBlank` (issue 096 §1), and the create form
labels it *(optional)* to match. So the Users list has been giving its second
column to a field that may be empty, while omitting the one value that is
mandatory, unique, and the thing an administrator actually needs: **the
username the person types to log in.**

Issue 105 made this sharply worse. Student logins are now 8 random digits, and
nothing on the list showed them — so identifying "which account is this?"
required opening the edit dialog one row at a time. The email column read
`28339473@example-township-school.edu`, which *looks* like it answers the
question and only does so by an accident of how the fixture builds addresses; a
real school issuing `firstname.lastname@` addresses would show nothing useful at
all.

## 2. Search had the same blind spot

`UserListQueryRepository` matched one column:

```sql
and lower(u.display_name) like :search
```

So an administrator holding a credential slip — which prints the username and
the student's name, and no email — could search by the name but not by the
number beside it. With 8-digit logins that is the more likely thing to be
holding.

## 3. The fix

- **The list's second column becomes the username.** Email is not relocated
  elsewhere on the row; it stays available in the edit dialog, which is what the
  user asked for and where an optional contact field belongs.
- **Search matches username as well as display name**, in one `or` group so the
  existing filters still `and` together correctly.
- The search placeholder stops saying "Search by name…", because a placeholder
  that understates what the box does is the reason nobody tries the other thing.

Both the desktop table and the mobile card carry the change; the card showed
email under the name for the same reason the table did.

### Why not show both

Considered and rejected. The row already carries name, role, grade, class,
status and last-login, and issue 095's work on this table was about a column set
that had outgrown its width — a comment on the email cell still records the
truncation added because a long address "pushed every other column narrower".
Adding a ninth column re-creates that problem to surface a field that is
optional, frequently empty, and one click away.

## 4. Verification

`mvn test` 333 passing, `pnpm build` clean, en/zh key parity re-checked
programmatically (2003 keys, identical order). Deployed to the test server and
driven in a browser as `admin001`.

| Check | Result |
| :--- | :--- |
| Column header | **Username**, where Email was |
| Rows | `Alex L. / teacher001`, `Alex L.(李*红) / 28339473` — staff names and 8-digit student logins side by side |
| Any email left on the list | **none** |
| Email in the edit dialog | present, `32103431@example-township-school.edu` |
| Search by full username `28339473` | **1 total**, the right student |
| Search by partial username `teacher00` | **5**, all five teachers |
| Search by name `Reagan` | **1** |
| Placeholder | "Search by name or username…" |

### The bracketing, tested rather than assumed

`and A or B and C` binds `and` tighter than `or`, so an unbracketed username
clause would let every *other* filter apply to only half the search. A count
cannot show that — the query still returns rows, just the wrong ones. So it was
tested with a pair:

| | |
| :--- | :--- |
| search `Reagan` (a **student**) + role filter **Teacher** | **0 total** |
| same search + role filter **Student** (the control) | **1**, Reagan W. |

The control is the point: 0 on its own is equally consistent with "the filter
broke everything". `UserListQuerySqlTest` also asserts the bracketed string
directly and asserts the unbracketed form is absent, so the guard does not
depend on someone re-running this by hand.

**A note on how it is searched:** this box submits on Enter or the Search
button; it does not filter as you type. An initial check that fired synthetic
`input` events saw all 37 rows for every term and looked like a total failure —
it was the harness, not the feature.
