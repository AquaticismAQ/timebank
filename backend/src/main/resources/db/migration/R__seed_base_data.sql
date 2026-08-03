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

-- Two classes (A/B) per grade for the current academic year.
INSERT IGNORE INTO class (id, code, name_zh, name_en, grade_id, academic_year_id, created_at, updated_at) VALUES
    ('class_6A', '6A', '六年级A班', 'Grade 6 A', 'grade_6', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_6B', '6B', '六年级B班', 'Grade 6 B', 'grade_6', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_7A', '7A', '七年级A班', 'Grade 7 A', 'grade_7', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_7B', '7B', '七年级B班', 'Grade 7 B', 'grade_7', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_8A', '8A', '八年级A班', 'Grade 8 A', 'grade_8', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_8B', '8B', '八年级B班', 'Grade 8 B', 'grade_8', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_9A', '9A', '九年级A班', 'Grade 9 A', 'grade_9', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_9B', '9B', '九年级B班', 'Grade 9 B', 'grade_9', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_10A', '10A', '十年级A班', 'Grade 10 A', 'grade_10', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_10B', '10B', '十年级B班', 'Grade 10 B', 'grade_10', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_11A', '11A', '十一年级A班', 'Grade 11 A', 'grade_11', 'ay_2025_2026', NOW(3), NOW(3)),
    ('class_11B', '11B', '十一年级B班', 'Grade 11 B', 'grade_11', 'ay_2025_2026', NOW(3), NOW(3));

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
    ('tmpl_proposal_rejected', 'eventProposal.rejected', '活动提案未通过', 'Event Proposal Rejected', '你的活动提案未通过审核，请查看审核意见。', 'Your event proposal was not approved - please review the feedback.', NOW(3), NOW(3));