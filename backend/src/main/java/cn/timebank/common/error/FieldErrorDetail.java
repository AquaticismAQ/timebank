package cn.timebank.common.error;

/** One entry of contract §2.6's {@code error.fieldErrors}. */
public record FieldErrorDetail(String field, String code, String message) {
}
