-- Design/chinese/01-TimeBankDesign.md §6 specifies different annual/stage
-- (graduation-cumulative) service-hour requirements for middle school
-- (grades 6-8: 25h/year, 75h cumulative) versus high school (grades 9-11:
-- 75h/year, 300h cumulative) — a real, documented product requirement the
-- original single flat annual_required_minutes/stage_required_minutes pair
-- never modeled (it only ever held the high-school band's numbers, applied
-- uniformly to every grade). Splits into two explicit bands rather than a
-- per-grade table — only two distinct rule sets exist by design, not six.
ALTER TABLE rule_version
    ADD COLUMN middle_annual_required_minutes INT NOT NULL DEFAULT 1500 AFTER centi_coins_per_unit,
    ADD COLUMN middle_stage_required_minutes INT NOT NULL DEFAULT 4500 AFTER middle_annual_required_minutes,
    CHANGE COLUMN annual_required_minutes high_annual_required_minutes INT NOT NULL,
    CHANGE COLUMN stage_required_minutes high_stage_required_minutes INT NOT NULL;
