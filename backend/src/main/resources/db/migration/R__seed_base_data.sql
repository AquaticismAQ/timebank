-- Base-data seed (B2.2/B2.4/B2.8/B2.10/B2.11/B2.6). Reference/config data,
-- repeatable per D7. All IDs are deterministic natural keys for the same
-- idempotency reason as R__seed_permissions_and_roles.sql.

-- Grades: static dictionary, not year-scoped.
INSERT IGNORE INTO grade (id, code, name_zh, name_en, sequence) VALUES
    ('grade_6', 'grade_6', '六年级', 'Grade 6', 6),
    ('grade_7', 'grade_7', '七年级', 'Grade 7', 7),
    ('grade_8', 'grade_8', '八年级', 'Grade 8', 8),
    ('grade_9', 'grade_9', '九年级', 'Grade 9', 9),
    ('grade_10', 'grade_10', '十年级', 'Grade 10', 10),
    ('grade_11', 'grade_11', '十一年级', 'Grade 11', 11),
    -- Grade 12 completes the senior stage (Design/*/14-AnnualRollover.md §2:
    -- stage 1 is 6-7-8, stage 2 is 9-10-11-12). Without it the rollover has
    -- nowhere to promote grade 11 to, so the senior stage could never finish.
    ('grade_12', 'grade_12', '十二年级', 'Grade 12', 12);

-- Academic years. The current one carries every seeded student and record; the
-- prior year is deliberately left empty.
--
-- Issue 093 §1: the reports' academic-year filter cannot be *tested* against a
-- single year. With only one row here, a filter that is accepted and silently
-- ignored looks identical to one that works — which is the exact bug §1 was.
-- An empty prior year gives every check something to distinguish: scoped to it
-- the reports return nothing, against a populated current year.
--
-- DERIVED FROM TODAY, NOT LITERALS (issue 100). These were two hardcoded rows,
-- and the "current" one had already ended: on 2026-09-18 the flagged year was
-- 2025-09-01..2026-08-31. That is not cosmetic — `end_date` is a real query
-- bound (`ReportQueryRepository` joins on it, annual progress sums
-- `service_date between :yearStart and :yearEnd`), while `integration-test`
-- dates every fixture record relative to *now*. So a contributor seeding a
-- fresh server got records sitting outside the year the reports scope to, and
-- annual progress that silently undercounted. A literal year is wrong from the
-- first September after it is typed; this cannot be.
--
-- A school year is taken to start on 1 September, so anything from September
-- onwards belongs to the year named for the calendar year it starts in.
SET @ay_start := IF(MONTH(CURDATE()) >= 9, YEAR(CURDATE()), YEAR(CURDATE()) - 1);
SET @ay_current_id := CONCAT('ay_', @ay_start, '_', @ay_start + 1);
SET @ay_prior_id := CONCAT('ay_', @ay_start - 1, '_', @ay_start);

INSERT IGNORE INTO academic_year (id, code, start_date, end_date, is_current, created_at, updated_at) VALUES
    (@ay_prior_id, CONCAT(@ay_start - 1, '-', @ay_start),
     MAKEDATE(@ay_start - 1, 1) + INTERVAL 8 MONTH,
     MAKEDATE(@ay_start, 1) + INTERVAL 8 MONTH - INTERVAL 1 DAY, 0, NOW(3), NOW(3)),
    (@ay_current_id, CONCAT(@ay_start, '-', @ay_start + 1),
     MAKEDATE(@ay_start, 1) + INTERVAL 8 MONTH,
     MAKEDATE(@ay_start + 1, 1) + INTERVAL 8 MONTH - INTERVAL 1 DAY, 1, NOW(3), NOW(3));

-- Exactly one current year, whatever was flagged before. `INSERT IGNORE` alone
-- cannot do this: on a database seeded in an earlier school year the old row
-- still carries the flag, and two current years is worse than a stale one —
-- `ReportAcademicYearResolver` picks whichever the database returns first.
UPDATE academic_year SET is_current = (id = @ay_current_id), updated_at = NOW(3)
    WHERE is_current <> (id = @ay_current_id);

-- Two classes (A/B) per grade for the current academic year. `name` is the raw
-- class label ONLY — never grade-prefixed, never locale-decorated (issue 010:
-- collapsed from name_zh/name_en, which duplicated a hand-written "班" suffix
-- per row; the frontend now assembles the localized display string itself). A
-- grade-prefixed class name double-prefixes once combined with grade display
-- (issue 005 — "Grade 9 Grade 9 B") — `name` stays a bare label to avoid that.
INSERT IGNORE INTO class (id, code, name, grade_id, academic_year_id, created_at, updated_at) VALUES
    ('class_6A', '6A', 'A', 'grade_6', @ay_current_id, NOW(3), NOW(3)),
    ('class_6B', '6B', 'B', 'grade_6', @ay_current_id, NOW(3), NOW(3)),
    ('class_7A', '7A', 'A', 'grade_7', @ay_current_id, NOW(3), NOW(3)),
    ('class_7B', '7B', 'B', 'grade_7', @ay_current_id, NOW(3), NOW(3)),
    ('class_8A', '8A', 'A', 'grade_8', @ay_current_id, NOW(3), NOW(3)),
    ('class_8B', '8B', 'B', 'grade_8', @ay_current_id, NOW(3), NOW(3)),
    ('class_9A', '9A', 'A', 'grade_9', @ay_current_id, NOW(3), NOW(3)),
    ('class_9B', '9B', 'B', 'grade_9', @ay_current_id, NOW(3), NOW(3)),
    ('class_10A', '10A', 'A', 'grade_10', @ay_current_id, NOW(3), NOW(3)),
    ('class_10B', '10B', 'B', 'grade_10', @ay_current_id, NOW(3), NOW(3)),
    ('class_11A', '11A', 'A', 'grade_11', @ay_current_id, NOW(3), NOW(3)),
    ('class_11B', '11B', 'B', 'grade_11', @ay_current_id, NOW(3), NOW(3)),
    ('class_12A', '12A', 'A', 'grade_12', @ay_current_id, NOW(3), NOW(3)),
    ('class_12B', '12B', 'B', 'grade_12', @ay_current_id, NOW(3), NOW(3)),
    -- A named (not single-letter) class, as some schools use house or
    -- programme names instead of A/B — issue 006: nothing in seed data or the
    -- fixed-width UI assumptions had ever exercised a className longer than
    -- one character before this. The name is deliberately invented; the
    -- previous value here was a real school's class name.
    ('class_10_luna', '10-Luna', 'Luna', 'grade_10', @ay_current_id, NOW(3), NOW(3));

-- Service categories, matching the frontend mock's SERVICE_CATEGORIES.
INSERT IGNORE INTO service_category (id, code, name_zh, name_en, active, created_at, updated_at) VALUES
    ('cat_campus', 'cat_campus', '校园服务', 'Campus Service', 1, NOW(3), NOW(3)),
    ('cat_community', 'cat_community', '社区服务', 'Community Service', 1, NOW(3), NOW(3)),
    ('cat_welfare', 'cat_welfare', '公益服务', 'Welfare Service', 1, NOW(3), NOW(3)),
    ('cat_education', 'cat_education', '教育服务', 'Education Service', 1, NOW(3), NOW(3)),
    ('cat_environment', 'cat_environment', '环保服务', 'Environmental Service', 1, NOW(3), NOW(3)),
    ('cat_other', 'cat_other', '其他', 'Other', 1, NOW(3), NOW(3));

-- School info (D8: single row, single-tenant per deployment).
--
-- `full_name` must name the SAME school as `name`. It used to read "Example
-- Township Middle School", which is the placeholder `integration-test` gives a
-- **partner** school (`mock-data.ts`) — so the host school's full name was a
-- partner's name, and every surface that prefers `full_name` introduced the
-- product under the wrong organisation. Keep host-school placeholders and
-- partner placeholders visibly distinct; `Docs/README.md` §2.8 now says which
-- is which.
INSERT IGNORE INTO school_info (id, name, full_name, student_dev_center, contact, default_language, current_academic_year_code, updated_at) VALUES
    ('school_default', 'Example City HD', 'Example City HD Middle School', 'Student Development Center H300', 'Teacher Wei Chen', 'zh-CN',
     -- Derived, like the academic_year rows above. A literal here went stale the
     -- same way theirs did: on a fresh install today it claimed 2025-2026 while
     -- the academic_year table said 2026-2027 was current.
     CONCAT(@ay_start, '-', @ay_start + 1), NOW(3));

-- The singleton school Organization row (V17's schema, issue 03-ServiceRecordFormOptimization
-- §4.1) — mirrors school_info's own values rather than being derived from it, since this
-- repeatable migration and school_info's own seed above are independent INSERT IGNOREs with
-- no ordering guarantee relative to each other within the same file run.
INSERT IGNORE INTO organization (id, name, type, description, contact_name, contact_email, contact_phone, address,
                                  status, is_school_organization, version, created_at, updated_at) VALUES
    ('org_school_default', 'Example City HD', 'SCHOOL', 'Example City HD Middle School', 'Teacher Wei Chen', NULL, NULL, NULL,
     'ACTIVE', 1, 1, NOW(3), NOW(3));

-- Backfill (issue 03 §10 D5): every project must have exactly one HOST_SCHOOL row.
-- New projects get theirs from ProjectServiceImpl.createDraft()/.createFromProposal()
-- at creation time (via OpaqueIds.generate("porg"), 31 chars, safely under the 32-char
-- column); this backfill only ever matters for projects that pre-date V18. Ids here are
-- MD5(...) (exactly 32 hex chars) rather than CONCAT('porg_host_', p.id) — that CONCAT
-- form is 41+ chars against a VARCHAR(32) column, silently truncated by MySQL's default
-- non-strict mode rather than rejected outright, which both loses the human-readable
-- prefix's whole point and risks two different projects' truncated ids colliding at
-- scale (caught live: every row on the shared test server's small dataset happened to
-- stay unique by chance, not by correctness). INSERT IGNORE is a no-op once a
-- HOST_SCHOOL row already exists for a project (checked by project_id+role below, not
-- by id), so changing the id formula here doesn't re-trigger inserts for rows this
-- migration already created under the old, truncated scheme.
INSERT IGNORE INTO project_organization (id, project_id, organization_id, role, sort_order)
SELECT MD5(CONCAT('host', p.id)), p.id, so.id, 'HOST_SCHOOL', 0
FROM project p CROSS JOIN organization so
WHERE so.is_school_organization = 1
  AND NOT EXISTS (SELECT 1 FROM project_organization po WHERE po.project_id = p.id AND po.role = 'HOST_SCHOOL');

-- Backfill: an existing single organization_id link becomes a PARTNER row, unless
-- it already points at the school org itself (redundant with the HOST_SCHOOL row
-- just inserted above, and would collide with it as an organization_id duplicate).
INSERT IGNORE INTO project_organization (id, project_id, organization_id, role, sort_order)
SELECT MD5(CONCAT('partner', p.id)), p.id, p.organization_id, 'PARTNER', 1
FROM project p
WHERE p.organization_id IS NOT NULL
  AND p.organization_id NOT IN (SELECT id FROM organization WHERE is_school_organization = 1)
  AND NOT EXISTS (
      SELECT 1 FROM project_organization po
      WHERE po.project_id = p.id AND po.organization_id = p.organization_id AND po.role = 'PARTNER');

-- Feature flags: everything off (Design.md §14 — flags only, nothing flagged is built).
INSERT IGNORE INTO feature_flag (code, enabled, updated_at) VALUES
    ('pov', 0, NOW(3)),
    ('redemption', 0, NOW(3)),
    ('borrowing', 0, NOW(3)),
    ('appreciation', 0, NOW(3));

-- Default rule version (§22.1: 0.5h rounding, 1h->1 coin, honour tiers
-- 25/75/150/300h; middle school 25h/year+75h cumulative, high school
-- 75h/year+300h cumulative — matching Design/chinese/01-TimeBankDesign.md
-- §4.2/§6/§8.1).
--
-- `effective_from` is DERIVED, and it is load-bearing rather than descriptive.
-- Approving a record resolves the rule **at that record's service date**
-- (`ReviewDecisionTransaction`) and throws "No rule is in effect for service
-- date …" when none is, so any record older than this value cannot be approved
-- at all. It was the literal '2025-09-01' under a comment claiming it tracked
-- the current academic year — which it did not, and which happened to still
-- work only because that date was in the past.
--
-- The prior year's start, not the current one: `integration-test` seeds a full
-- year of history behind the current one (issue 103 §3.1), and a real school
-- importing last year's records needs the same room.
INSERT IGNORE INTO rule_version (
    id, status, rounding_minutes, minutes_per_coin_unit, centi_coins_per_unit,
    middle_annual_required_minutes, middle_stage_required_minutes,
    high_annual_required_minutes, high_stage_required_minutes,
    honour_bronze_minutes, honour_silver_minutes, honour_gold_minutes, honour_excellent_minutes,
    effective_from, effective_to, created_at, updated_at, published_at, created_by
) VALUES (
    'rule_default_2025', 'PUBLISHED', 30, 60, 100,
    1500, 4500,
    4500, 18000,
    1500, 4500, 9000, 18000,
    MAKEDATE(@ay_start - 1, 1) + INTERVAL 8 MONTH, NULL, NOW(3), NOW(3), NOW(3), NULL
);

-- Mark the current academic year as already rolled over
-- (Design/*/14-AnnualRollover.md §3).
--
-- A newly installed or freshly seeded system has no rollover history, but its
-- roster is already in the grades it is supposed to be in — the import put it
-- there. Without this row the daily job reads "this year has never been rolled
-- over", correctly concludes it is overdue, and rolls it over a second time:
-- everyone in grades 8 and 12 graduates and loses their login, on a database
-- that was correct before it ran.
--
-- The establishing import *is* that year's rollover, so the system starts in a
-- rolled-over state and the first real run is next September. `INSERT IGNORE`
-- with a year-derived id means this fires once per academic year, so a
-- deployment that spans a September does not re-mark the new year and block a
-- rollover that genuinely is due.
INSERT IGNORE INTO annual_rollover_run
    (id, academic_year_id, status, triggered_by, started_at, finished_at)
VALUES (CONCAT('aro_bootstrap_', @ay_current_id), @ay_current_id, 'SUCCEEDED',
        'bootstrap', NOW(3), NOW(3));

-- Notification templates: representative examples proving the mechanism.
-- The full catalog is finalized as B3/B4 build the transactions that need them.
-- serviceRecord.approved/.returned proved the mechanism in B2; .rejected is
-- added now as B3's approval transaction becomes the first real writer of
-- a notification row (rather than just rendering one).
INSERT IGNORE INTO notification_template (id, code, title_zh, title_en, body_zh, body_en, created_at, updated_at) VALUES
    ('tmpl_record_approved', 'serviceRecord.approved', '服务记录已通过', 'Service Record Approved', '你提交的服务记录已通过审核，时间币已入账。', 'Your service record has been approved and time-coins credited.', NOW(3), NOW(3)),
    ('tmpl_record_returned', 'serviceRecord.returned', '服务记录需要补充', 'Service Record Needs More Information', '你提交的服务记录被退回，请查看审核意见并补充材料。', 'Your service record was returned - please review the feedback and resubmit.', NOW(3), NOW(3)),
    ('tmpl_record_rejected', 'serviceRecord.rejected', '服务记录未通过', 'Service Record Rejected', '你提交的服务记录未通过审核，请查看审核意见。', 'Your service record was not approved - please review the feedback.', NOW(3), NOW(3)),
    -- B4's proposal-approval transaction is the second real writer of a notification row.
    ('tmpl_proposal_approved', 'eventProposal.approved', '活动提案已通过', 'Event Proposal Approved', '你的活动提案已通过审核，正式项目已生成。', 'Your event proposal has been approved and an official project has been generated.', NOW(3), NOW(3)),
    ('tmpl_proposal_changes_requested', 'eventProposal.changesRequested', '活动提案需要修改', 'Event Proposal Needs Changes', '你的活动提案需要修改，请查看审核意见并重新提交。', 'Your event proposal needs changes - please review the feedback and resubmit.', NOW(3), NOW(3)),
    ('tmpl_proposal_rejected', 'eventProposal.rejected', '活动提案未通过', 'Event Proposal Rejected', '你的活动提案未通过审核，请查看审核意见。', 'Your event proposal was not approved - please review the feedback.', NOW(3), NOW(3)),
    -- 07-RoleFinetune.md §2.10: goes to projects.publish holders covering the proposal's grade, not the proposer.
    ('tmpl_proposal_approved_needs_publish', 'eventProposal.approvedNeedsPublish', '活动提案已批准，草稿项目待发布', 'Event Proposal Approved — Draft Project Needs Publishing', '一个活动提案已批准并生成了草稿项目，需要你发布后学生才能报名。', 'An event proposal was approved and a draft project was generated - it needs to be published before students can register.', NOW(3), NOW(3)),
    -- B5's caseflow decisions are the third real writer of a notification row.
    ('tmpl_case_resolved', 'case.resolved', '申诉/补交已处理', 'Case Resolved', '你提交的申诉/补交材料已处理完毕。', 'Your appeal/supplement has been resolved.', NOW(3), NOW(3)),
    ('tmpl_case_needs_information', 'case.needsInformation', '申诉/补交需要补充信息', 'Case Needs More Information', '你提交的申诉/补交材料需要补充信息，请查看审核意见并重新提交。', 'Your appeal/supplement needs more information - please review the feedback and resubmit.', NOW(3), NOW(3)),
    ('tmpl_case_rejected', 'case.rejected', '申诉/补交未通过', 'Case Rejected', '你提交的申诉/补交材料未通过，请查看审核意见。', 'Your appeal/supplement was rejected - please review the feedback.', NOW(3), NOW(3)),
    -- Issue 137: publishing a project is the fourth real writer of a notification
    -- row, and the first that fans out — it goes to every active student in the
    -- project's eligible grades and every staff account assigned to one of them.
    ('tmpl_project_published', 'project.published', '有新项目可以报名', 'A New Project Is Open', '本年级有新的志愿服务项目开放报名，去项目中心看看吧。', 'A new volunteer project is open to your grade - take a look in the Project Center.', NOW(3), NOW(3)),
    -- Issue 155: the first notification a person sends on purpose rather than
    -- one a state change fans out. Student Management's Remind button, which
    -- until now showed the teacher a toast and sent the student nothing.
    ('tmpl_student_progress_reminder', 'student.serviceProgressReminder', '志愿服务时长提醒', 'A Reminder About Your Service Hours', '老师提醒你关注本学年的志愿服务时长进度，记得参加项目并及时提交服务记录。', 'A teacher has reminded you to check your volunteer service progress this school year - take part in a project and submit your service records.', NOW(3), NOW(3));

-- System settings: a single fixed row (B5.5/B5.12).
INSERT IGNORE INTO system_settings (id, notification_poll_interval_seconds, version, updated_at, updated_by) VALUES
    ('default', 30, 1, NOW(3), NULL);