# 125 — The verified-hours field states no unit

**Status:** **Fixed and deployed** 2026-09-20, same day. Filed on Aquaticism's report
("in 'New project' dialog, the 'Verified Hours' need a unit(hour)").

## 1. What is wrong

The field is a bare number with a `0.5` step, and nothing on screen says what
the number counts. The Chinese label — `认定时长`, *certified duration* — names a
quantity without its unit, so `2` could as easily be minutes.

It also disagreed with its own neighbours. The bundle already settles this shape
elsewhere:

| Key | English | Chinese |
| :--- | :--- | :--- |
| `student.proposals.estimatedHours` | Estimated Duration (hours) | 预计时长（小时）|
| `student.proposals.suggestedHours` | Suggested Certified Hours | 建议认定时长（小时）|
| `admin.projects.recognizedHours` | Certified Hours | 认定时长（小时）|
| **`admin.projects.verifiedHours`** | **Verified Hours** | **认定时长** |

The last row is the same concept as the row above it, in the same namespace,
missing the unit in both languages.

## 2. What it should say

`Verified Duration (hours)` / `认定时长（小时）`.

**Not `Verified Hours (hours)`**, which is what "add the unit" literally asks
for and reads as a stammer. The English said "Hours" where the Chinese said
"时长" (*duration*) — so the two were never translations of each other, and the
parenthetical only becomes redundant because of that mismatch. Naming the
quantity *duration* in both languages lets the unit sit where every neighbouring
field already puts it.

## 3. Both dialogs, not just the reported one

New Project and Edit Project render the same key. Fixing only the one Aquaticism was
looking at would have left the pair disagreeing about what the same field means
— the parity problem issues 050-054 were a sweep for.

## 4. Verification

Read off the deployed build, in both dialogs and both languages:

```
zh-CN  New Project: ["认定时长（小时）"]     Edit: ["认定时长（小时）"]
en     New Project: ["Verified Duration (hours)"]  Edit: ["Verified Duration (hours)"]
```

i18n parity: 2124 keys each, identical order.

## 5. What it must not do

- **Must not put the unit only in Chinese.** The English label named the unit by
  accident of wording, not by design; a reader of either language deserves the
  same statement.
- **Must not fix one dialog.** §3.
- **Must not change the stored value.** The field still reads and writes minutes
  (`recognizedMinutes`); the label describes what is displayed, and hours are
  what is displayed. Durations stay integer minutes on the wire.
