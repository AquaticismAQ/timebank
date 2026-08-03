package cn.timebank.project.domain;

/** Contract §3.1. Determined by both time and management action — the frontend never derives it independently. */
public enum ProjectStatus {
    DRAFT, PUBLISHED, REGISTRATION_OPEN, IN_PROGRESS, COMPLETED, CLOSED, ARCHIVED
}
