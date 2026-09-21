# 151 — The accounts page searches by a number it never shows

**Status:** **Fixed and deployed 2026-09-21.** Aquaticism's report: on the Time Coin
Accounts page (Admin/Teacher Portal), the search box finds a student by name **or
student number**, but the number does not appear on the student cards.

## 1. The mismatch

The placeholder says "Search student name or ID…", and the server honours it —
`search=3VVW4ZDG` returns exactly that student.

The card then shows a name, a grade and a class, and nothing else identifying.
So an operator who searched by number gets one row back and no way to confirm it
is the right person: the value they typed is not on the screen anywhere. It is
also the identifier a teacher actually holds — the credential slip and the
service-record number carry the student number, not the display name, and
display names are masked (`Alex L.(李*红)`) and repeat across a school.

The data was never missing. `Student.studentNumber` is already on the objects
this page renders; the card simply did not print it.

## 2. Fix

The card gains a line: the name, then `studentNumber · grade/class` beneath it —
the same name-over-number stack the Student Management list already uses.

Nothing else changes: the search, the filters and the balances were all correct.

**The first attempt was worse than the problem.** Putting the number beside the
name on the existing line, which looked like the tidier change, meant that at
390px the name truncated instead — `Taylor …` where a masked name had been. The
number is useful; the name is not optional. Hence its own line.

## 3. Verification

On the deployed site, at 390px and at 1280px, as **both** roles that reach this
page.

```
390px, teacher
  Alex L.(李*红)
  HKTZ4HKV · 六年级, B班          full name, number beneath, nothing clipped

1280px, teacher — the reported flow
  search "12A1K705"  →  1 total, card reads
  Payton Z.(周*杰)
  12A1K705 · 六年级, A班          the value searched for is now on the result

1280px, admin (English, school-wide, 29 accounts)
  Oakley Z.(赵*敏)
  708MEA5B · G12, Class A
```

The first attempt was caught by screenshotting at 390px rather than reading the
accessibility tree, which showed the truncated name as ordinary text and said
nothing about it being cut off.

## 4. What it must not do

- **Must not push the card's layout wide on a phone.** Mobile-first is a hard
  requirement (`Docs/frontend/Design.md`), and this line already carries a name.
- **Must not show a student number where a student could see another's.** This
  is the Admin/Teacher Portal, already scoped; §4.2 of the security design is
  explicit that the number is not a credential and displaying it to staff is not
  a disclosure.
