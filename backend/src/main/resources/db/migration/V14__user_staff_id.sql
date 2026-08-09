-- Issue 003: `staffId` for teacher/admin accounts (mirrors the existing
-- `student_id`, which was already a column but never enforced unique or
-- actually populated by UserServiceImpl.create() before this). No FK to
-- `student.id` — same module-boundary reasoning as the original
-- `student_id` column's comment (V3__iam.sql): `iam` validates existence
-- via `student`'s own application-layer service, not a cross-module FK.
ALTER TABLE user
    ADD COLUMN staff_id VARCHAR(64) NULL,
    ADD UNIQUE KEY uk_user_staff_id (staff_id),
    ADD UNIQUE KEY uk_user_student_id (student_id);
