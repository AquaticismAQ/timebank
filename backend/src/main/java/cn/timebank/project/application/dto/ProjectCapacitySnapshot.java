package cn.timebank.project.application.dto;

/** What {@code enrollment} needs from a locked read of the project row (B4.7) — never the entity itself. */
public record ProjectCapacitySnapshot(String projectId, int capacity, String status) {
}
