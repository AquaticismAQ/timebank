package cn.timebank.organization.application.dto;

/** Contract §4.16's `GET /school/info` shape. */
public record SchoolInfoDto(String name, String fullName, String studentDevCenter, String contact, String academicYear) {
}
