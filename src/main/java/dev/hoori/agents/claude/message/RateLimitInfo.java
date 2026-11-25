package dev.hoori.agents.claude.message;

import java.util.Map;

/**
 * Rate limit status.
 *
 * @param status        {@code allowed}, {@code allowed_warning} or {@code rejected}
 * @param resetsAt      Unix timestamp when the window resets
 * @param rateLimitType {@code five_hour}, {@code seven_day}, {@code seven_day_opus}, {@code seven_day_sonnet},
 *                      {@code overage}
 * @param utilization   fraction of the limit consumed (0.0 - 1.0)
 * @param raw           the full raw object, including fields not modelled here
 */
public record RateLimitInfo(
        String status,
        Long resetsAt,
        String rateLimitType,
        Double utilization,
        String overageStatus,
        Long overageResetsAt,
        String overageDisabledReason,
        Map<String, Object> raw) {
}
