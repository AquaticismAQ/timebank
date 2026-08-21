package cn.timebank.organization.application.dto;

public final class SchoolInfoRequests {

    private SchoolInfoRequests() {
    }

    /**
     * `PATCH /school/info` — sysadmin-only (not merely `settings.manage`,
     * which admin also holds post role-merge; see `SchoolInfoController`).
     * No optimistic-locking version: a single, rarely-edited row with
     * exactly one class of editor, unlike the versioned entities elsewhere
     * in this codebase.
     */
    public record UpdateSchoolInfoRequest(String name, String fullName, String studentDevCenter, String contact) {
    }
}
