package cn.timebank.common.web;

import cn.timebank.common.error.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * No business meaning — a fixed target for verifying the envelope and
 * error-handling shapes end to end (B0.3/B0.4 exit criteria) and for a
 * manual smoke test once deployed. Every later phase has a real endpoint
 * to test against; B0 does not, so this stands in.
 *
 * {@code @PreAuthorize("permitAll()")} is explicit rather than omitted —
 * B1.6's permission-annotation scan (every mapped controller method must
 * declare one) makes no exception for demo endpoints, on purpose: a
 * controller with no annotation and one with an explicit {@code
 * permitAll()} must not look the same to that test.
 */
@RestController
public class PingController {

    @GetMapping("/api/v1/ping")
    @PreAuthorize("permitAll()")
    public ApiResponse<PingPayload> ping() {
        return ApiResponse.of(new PingPayload("ok"));
    }

    /** Deliberately throws so the error envelope shape can be verified against a real request. */
    @GetMapping("/api/v1/ping/fail")
    @PreAuthorize("permitAll()")
    public ApiResponse<PingPayload> fail(@RequestParam(defaultValue = "not-found") String as) {
        throw switch (as) {
            case "forbidden" -> ApiException.forbidden("Demonstration forbidden error.");
            case "conflict" -> ApiException.stateConflict("Demonstration conflict error.");
            default -> ApiException.notFound("Demonstration not-found error.");
        };
    }

    public record PingPayload(String status) {
    }
}
