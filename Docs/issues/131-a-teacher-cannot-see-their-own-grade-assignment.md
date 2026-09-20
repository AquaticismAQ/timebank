# 131 — A teacher cannot see their own grade assignment

**Status:** **Fixed and deployed** 2026-09-20, same day. Filed on Aquaticism's report:
nowhere in the app shows a teacher which grades they are assigned to.

## 1. Why this is not cosmetic

It was true and harmless until this week. Since issues 129 and 130, a teacher's
grade assignment decides **what they may edit and publish** — so the first time
most teachers will meet it is a project whose menu says *View* where a
colleague's says *Edit*, with nothing on screen explaining the difference.

The data was already on the client: `/me` returns `user.scopeGradeIds`, and
`StudentList` has used it since issue 004 to narrow its grade dropdown. Nothing
ever showed it to the person it describes.

## 2. Three states, all worth saying

| Who | Card shows |
| :--- | :--- |
| Teacher with grades | the grade names, as chips |
| `admin` / `sysadmin` | **The whole school** — they carry no grade bond because they are school-wide, and saying so beats an empty row |
| Teacher with none | **No grades assigned**, in warning colours |

The third is the one that earns its place. Such a teacher resolves to a `Self`
scope and can edit only projects they own (issue 129 §3) — a real, surprising
limitation they would otherwise have no way to discover.

Ids are resolved to names through the same dictionary every other screen uses,
so the card follows the reader's language rather than carrying a second copy of
the grade list.

## 3. Verification

The deployed page, across all three states and both languages:

```
teacher005 (zh)  scope ["grade_10","grade_11","grade_12"]  →  负责年级： 十年级 十一年级 十二年级
teacher005 (en)  same scope                                →  Responsible for: Grade 10 Grade 11 Grade 12
admin001   (zh)  scope []                                  →  负责年级： 全校
teacher001 (zh)  scope ["grade_6"]                         →  负责年级： 六年级
```

Four accounts rather than one, because a card that printed the same thing for
everyone would pass a single-account check.

### A false alarm worth recording

An earlier run of this check showed `Grade 10` on an otherwise Chinese page, and
the obvious conclusion — that `Accept-Language` was wrong — was wrong. The app
stores `zh`, and `http.ts` maps exactly that to `Accept-Language: zh-CN`; the
English names came from the probe writing `timebank-lang=zh-CN` into
`localStorage` directly, a value the app itself never writes. Driving the real
language switcher produces `zh-CN` on the wire and Chinese grade names.

**This also explains an earlier observation** of English category names on a
Chinese Project Management page: same cause, same harness, not a defect. A test
that sets up state by hand can invent a state the product cannot reach.

## 4. What it must not do

- **Must not show an empty row for an administrator.** §2 — school-wide is the
  answer, not the absence of one.
- **Must not hide the unassigned case.** §2.
- **Must not print grade ids.** They are opaque; the dictionary has names.
- **Must not carry its own copy of the grade list.** One dictionary, one set of
  names, one language rule.
