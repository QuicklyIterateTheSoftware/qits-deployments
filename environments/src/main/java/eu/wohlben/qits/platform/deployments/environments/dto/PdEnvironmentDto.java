package eu.wohlben.qits.platform.deployments.environments.dto;

import java.time.Instant;
import java.util.List;

/**
 * One environment: a name, the branch it listens to, and its bundle network.
 *
 * <p>{@code applications} is the tier's own services, attached by the boundary when one environment
 * is fetched and left <b>null</b> on listings — the difference between "this tier holds nothing"
 * and "you did not ask". It never carries the platform services: those belong to no tier, and a
 * reader that took this field for the whole answer would silently miss qits-idp and this component.
 * {@code GET /platform-deployments/api/environments/{id}/links} is the question that composes both.
 *
 * <p>{@code platform} is true on exactly one environment: the tier whose branch deploys the platform
 * plane. It says nothing about what this tier <em>holds</em> — a platform service is in every
 * environment either way — only about which branch is allowed to roll it.
 */
public record PdEnvironmentDto(
    String id,
    String name,
    String branch,
    String network,
    boolean platform,
    Instant createdAt,
    List<PdApplicationDto> applications) {}
