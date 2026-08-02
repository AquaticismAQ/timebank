-- B1: identity, authorization, and data scope (Design.md D4, §10, §15).

CREATE TABLE user (
    id                     VARCHAR(32)  NOT NULL,
    username               VARCHAR(64)  NOT NULL,
    password_hash          VARCHAR(100) NOT NULL,
    display_name           VARCHAR(100) NOT NULL,
    email                  VARCHAR(255) NULL,
    student_id             VARCHAR(32)  NULL, -- no FK: the student table doesn't exist until B2
    status                 VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    preferred_language     VARCHAR(8)   NOT NULL DEFAULT 'zh-CN',
    failed_login_attempts  INT          NOT NULL DEFAULT 0,
    locked_until           DATETIME(3)  NULL,
    version                BIGINT       NOT NULL DEFAULT 1,
    created_at             DATETIME(3)  NOT NULL,
    updated_at             DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Roles are configuration, not code (Design.md §5's watch-out): a school
-- can add roles beyond the four seeded defaults, so this is a real table,
-- never a Java enum.
CREATE TABLE role (
    id                       VARCHAR(32)  NOT NULL,
    code                     VARCHAR(32)  NOT NULL,
    name                     VARCHAR(100) NOT NULL,
    -- Session idle timeout for a principal holding this role (contract §2.2's
    -- "8h staff / 12h student, configurable"). A user with several roles gets
    -- the shortest of them (§B1.3) — kept here, not hardcoded in Java, so a
    -- school can add a role without a code change (Design.md §5 watch-out).
    session_timeout_minutes INT          NOT NULL DEFAULT 480,
    version                 BIGINT       NOT NULL DEFAULT 1,
    created_at              DATETIME(3)  NOT NULL,
    updated_at              DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE permission (
    id           VARCHAR(32)  NOT NULL,
    code         VARCHAR(64)  NOT NULL,
    description  VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_role (
    user_id  VARCHAR(32) NOT NULL,
    role_id  VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES user (id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES role (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE role_permission (
    role_id        VARCHAR(32) NOT NULL,
    permission_id  VARCHAR(32) NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES role (id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES permission (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE password_reset_token (
    id          VARCHAR(32) NOT NULL,
    user_id     VARCHAR(32) NOT NULL,
    token_hash  VARCHAR(64) NOT NULL, -- SHA-256 hex of the one-time token; the token itself is never stored
    expires_at  DATETIME(3) NOT NULL,
    used_at     DATETIME(3) NULL,
    created_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_password_reset_token_hash (token_hash),
    KEY idx_password_reset_user (user_id),
    CONSTRAINT fk_password_reset_user FOREIGN KEY (user_id) REFERENCES user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Owned by the `security` module (cn.timebank.security.domain), not `iam` —
-- kept in this migration file for convenience since schema files don't need
-- to mirror Java package boundaries. The data-scope resolver's storage
-- (B1.7): which grades/classes/projects a teacher may see for a given
-- resource type. Empty for a teacher means no access, a valid ordinary
-- state, not a misconfiguration.
CREATE TABLE teacher_scope_assignment (
    id             VARCHAR(32) NOT NULL,
    user_id        VARCHAR(32) NOT NULL,
    resource_type  VARCHAR(32) NOT NULL, -- e.g. 'serviceRecords', 'projects', 'eventProposals'
    scope_type     VARCHAR(16) NOT NULL, -- GRADE | CLASS | PROJECT
    scope_value    VARCHAR(64) NOT NULL, -- grade/class/project identifier — opaque, not parsed
    created_at     DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_scope_user_resource (user_id, resource_type),
    CONSTRAINT fk_scope_assignment_user FOREIGN KEY (user_id) REFERENCES user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- login_log: same partitioning treatment as audit_log (B0.10) and for the
-- same reason (Design.md §16.3) — decided now because retrofitting it
-- later is expensive. No foreign key to `user`: MySQL InnoDB does not
-- support foreign keys on partitioned tables.
CREATE TABLE login_log (
    id                  VARCHAR(32)  NOT NULL,
    user_id             VARCHAR(32)  NULL,
    username_attempted  VARCHAR(64)  NOT NULL,
    success             TINYINT(1)   NOT NULL,
    failure_reason      VARCHAR(64)  NULL,
    ip_address          VARCHAR(64)  NULL,
    user_agent          VARCHAR(256) NULL,
    created_at          DATETIME(3)  NOT NULL,
    PRIMARY KEY (id, created_at),
    KEY idx_login_log_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
PARTITION BY RANGE (TO_DAYS(created_at)) (
    PARTITION p_2026_08 VALUES LESS THAN (TO_DAYS('2026-09-01')),
    PARTITION p_2026_09 VALUES LESS THAN (TO_DAYS('2026-10-01')),
    PARTITION p_2026_10 VALUES LESS THAN (TO_DAYS('2026-11-01')),
    PARTITION p_2026_11 VALUES LESS THAN (TO_DAYS('2026-12-01')),
    PARTITION p_2026_12 VALUES LESS THAN (TO_DAYS('2027-01-01')),
    PARTITION p_2027_01 VALUES LESS THAN (TO_DAYS('2027-02-01')),
    PARTITION p_2027_02 VALUES LESS THAN (TO_DAYS('2027-03-01')),
    PARTITION p_2027_03 VALUES LESS THAN (TO_DAYS('2027-04-01')),
    PARTITION p_2027_04 VALUES LESS THAN (TO_DAYS('2027-05-01')),
    PARTITION p_2027_05 VALUES LESS THAN (TO_DAYS('2027-06-01')),
    PARTITION p_2027_06 VALUES LESS THAN (TO_DAYS('2027-07-01')),
    PARTITION p_2027_07 VALUES LESS THAN (TO_DAYS('2027-08-01')),
    PARTITION p_future VALUES LESS THAN MAXVALUE
);
