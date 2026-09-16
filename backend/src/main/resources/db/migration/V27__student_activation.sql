-- Student identity and first-time onboarding, schema half
-- (Docs/optimization/10-StudentIdentityAndOnboarding.md §3.1).
--
-- Students arrive by bulk CSV import, so the account exists before anyone
-- has told the student it does. §2.4 settles first access as a single-use
-- activation code the student redeems by choosing their own password: no
-- password is ever generated server-side, and an account with no password
-- cannot be logged into. That guarantee is what the four changes below
-- make representable.

-- F-5: `password_hash` has been NOT NULL since V1, which is exactly why an
-- account could not exist without a password. Nullable now, and null is the
-- load-bearing state — it is the "never activated, cannot authenticate"
-- marker, not merely an absent optional field.
ALTER TABLE user
    MODIFY COLUMN password_hash VARCHAR(100) NULL;

-- F-6: `status` held ACTIVE/INACTIVE/LOCKED only. Two new values, and the
-- point of the second one is that it is *not* LOCKED: LOCKED pairs with
-- `locked_until` and means "brute-forced", while a slip that was printed and
-- never collected means "reissue it". An admin looking at the list must be
-- able to tell those apart, so §2.6 forbids overloading LOCKED.
--
--   PENDING_ACTIVATION  imported, code issued, never redeemed
--   ACTIVATION_EXPIRED  the code's window closed before anyone used it
--
-- VARCHAR(16) could not hold either name, so the column widens. Still a plain
-- string, not a MySQL ENUM — the same reason every other status column here
-- is: adding a value must not need an ALTER on a large table.
ALTER TABLE user
    MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE';

-- F-7: no must-change-password flag existed. Written by the activation and
-- admin-reset paths (§3.5/§3.7), which are not in this change; the column is
-- here so those land without a second migration. FALSE for every existing
-- row, which is correct: they all chose their own password already.
ALTER TABLE user
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE AFTER password_hash;

-- The activation code itself (§2.5). Stored hashed and looked up by hash, so
-- redemption needs no username — the code is its own lookup key, exactly as
-- password_reset_token above works, and for the same reason: the plaintext
-- code exists only on the printed slip, never at rest.
--
-- `redeemed_at` rather than a boolean: single-use is enforced by "is this
-- null", and when it stopped being null is worth keeping. `issued_by` is the
-- provenance line printed on every slip (§2.8) — it has to be on the row, not
-- only in the audit log, because the slip is generated from this table.
CREATE TABLE activation_code (
    id          VARCHAR(32) NOT NULL,
    user_id     VARCHAR(32) NOT NULL,
    code_hash   VARCHAR(64) NOT NULL, -- SHA-256 hex of the 10-char code; the code itself is never stored
    issued_at   DATETIME(3) NOT NULL,
    expires_at  DATETIME(3) NOT NULL, -- §2.6: issue + a configurable window, 7 days by default
    redeemed_at DATETIME(3) NULL,     -- NULL while unredeemed; single-use is "this is still NULL"
    issued_by   VARCHAR(32) NOT NULL, -- the admin who generated the batch
    PRIMARY KEY (id),
    UNIQUE KEY uk_activation_code_hash (code_hash),
    KEY idx_activation_code_user (user_id),
    -- The expiry sweep (§3.6) scans by window, and regeneration (§2.7) reads
    -- one student's outstanding codes; both want an index, neither wants a
    -- full scan of every code ever issued.
    KEY idx_activation_code_expires (expires_at, redeemed_at),
    CONSTRAINT fk_activation_code_user FOREIGN KEY (user_id) REFERENCES user (id),
    CONSTRAINT fk_activation_code_issued_by FOREIGN KEY (issued_by) REFERENCES user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
