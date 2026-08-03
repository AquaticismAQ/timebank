package cn.timebank.servicerecord.domain;

/** Contract §3.4 — set at creation, changes which fields §4.5 requires at submission, never which state machine applies. */
public enum SourceType {
    PROJECT, EXTERNAL, SELF
}
