package cn.timebank.file.domain;

/** Contract §4.6. A file in any status but {@code READY} cannot be bound to a submission (B3.5, enforced at submit). */
public enum FileStatus {
    PENDING, PROCESSING, READY, REJECTED
}
