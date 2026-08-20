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
    ('grade_11', 'grade_11', '十一年级', 'Grade 11', 11);

-- One current academic year, matching the frontend mock's dates.
INSERT IGNORE INTO academic_year (id, code, start_date, end_date, is_current, created_at, updated_at) VALUES
    ('ay_2025_2026', '2025-2026', '2025-09-01', '2026-08-31', 1, NOW(3), NOW(3));

-- Two classes (A/B) per grade for the current academic year. `name` is the raw
-- class label ONLY — never grade-prefixed, never locale-decorated (issue 010:
-- collapsed from name_zh/name_en, which duplicated a hand-written "班" suffix
-- per row; the frontend now assembles the localized display string itself). A
-- grade-prefixed class name double-prefixes once combined with grade display
-- (issue 005 — "Grade 9 Grade 9 B") — `name` stays a bare label to avoid that.
INSERT IGNORE INTO class (id, code, name, grade_id, academic_year_id, created_at, updated_at) VALUES
    ('class_6A', '6A', 'A', 'grade_6', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_6B', '6B', 'B', 'grade_6', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_7A', '7A', 'A', 'grade_7', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_7B', '7B', 'B', 'grade_7', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_8A', '8A', 'A', 'grade_8', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_8B', '8B', 'B', 'grade_8', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_9A', '9A', 'A', 'grade_9', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_9B', '9B', 'B', 'grade_9', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_10A', '10A', 'A', 'grade_10', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_10B', '10B', 'B', 'grade_10', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_11A', '11A', 'A', 'grade_11', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_11B', '11B', 'B', 'grade_11', 'ay_2025_2026', NOW(3), NOW(3)),
    -- A named (not single-letter) class, same as some real schools use house/
    -- program names instead of A/B — issue 006: nothing in seed data or the
    -- fixed-width UI assumptions had ever exercised a className longer than
    -- one character before this.
    ('class_10_mils', '10-Mils', 'Mils', 'grade_10', 'ay_2025_2026', NOW(3), NOW(3));

-- Service categories, matching the frontend mock's SERVICE_CATEGORIES.
INSERT IGNORE INTO service_category (id, code, name_zh, name_en, active, created_at, updated_at) VALUES
    ('cat_campus', 'cat_campus', '校园服务', 'Campus Service', 1, NOW(3), NOW(3)),
    ('cat_community', 'cat_community', '社区服务', 'Community Service', 1, NOW(3), NOW(3)),
    ('cat_welfare', 'cat_welfare', '公益服务', 'Welfare Service', 1, NOW(3), NOW(3)),
    ('cat_education', 'cat_education', '教育服务', 'Education Service', 1, NOW(3), NOW(3)),
    ('cat_environment', 'cat_environment', '环保服务', 'Environmental Service', 1, NOW(3), NOW(3)),
    ('cat_other', 'cat_other', '其他', 'Other', 1, NOW(3), NOW(3));

-- School info (D8: single row, single-tenant per deployment).
INSERT IGNORE INTO school_info (id, name, full_name, student_dev_center, contact, default_language, current_academic_year_code, updated_at) VALUES
    ('school_default', 'Example City HD', 'Example Township Middle School', 'Student Development Center H300', 'Teacher Wei Chen', 'zh-CN', '2025-2026', NOW(3));

-- The singleton school Organization row (V17's schema, issue 03-ServiceRecordFormOptimization
-- §4.1) — mirrors school_info's own values rather than being derived from it, since this
-- repeatable migration and school_info's own seed above are independent INSERT IGNOREs with
-- no ordering guarantee relative to each other within the same file run.
INSERT IGNORE INTO organization (id, name, type, description, contact_name, contact_email, contact_phone, address,
                                  status, is_school_organization, version, created_at, updated_at) VALUES
    ('org_school_default', 'Example City HD', 'SCHOOL', 'Example Township Middle School', 'Teacher Wei Chen', NULL, NULL, NULL,
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

-- Default rule version, published and effective from the current academic
-- year's start (§22.1: 0.5h rounding, 1h->1 coin, honour tiers 25/75/150/300h,
-- annual 75h / stage 300h — matching Design/chinese/01-TimeBankDesign.md §4.2/§8.1).
INSERT IGNORE INTO rule_version (
    id, status, rounding_minutes, minutes_per_coin_unit, centi_coins_per_unit,
    annual_required_minutes, stage_required_minutes,
    honour_bronze_minutes, honour_silver_minutes, honour_gold_minutes, honour_excellent_minutes,
    effective_from, effective_to, created_at, updated_at, published_at, created_by
) VALUES (
    'rule_default_2025', 'PUBLISHED', 30, 60, 100,
    4500, 18000,
    1500, 4500, 9000, 18000,
    '2025-09-01T00:00:00', NULL, NOW(3), NOW(3), NOW(3), NULL
);

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
    ('tmpl_case_rejected', 'case.rejected', '申诉/补交未通过', 'Case Rejected', '你提交的申诉/补交材料未通过，请查看审核意见。', 'Your appeal/supplement was rejected - please review the feedback.', NOW(3), NOW(3));

-- System settings: a single fixed row (B5.5/B5.12).
INSERT IGNORE INTO system_settings (id, notification_poll_interval_seconds, version, updated_at, updated_by) VALUES
    ('default', 30, 1, NOW(3), NULL);