package cn.timebank.common.web;

/** Contract §2.5: every response carries {@code meta.requestId}. */
public record ResponseMeta(String requestId) {
}
