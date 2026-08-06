package eu.wohlben.qits.platform.deployments.environments.dto;

import eu.wohlben.qits.platform.deployments.environments.entity.PdDeploymentTarget;

/**
 * One service present in an environment, as a reconciliation needs it: what it is called, which
 * plane it is on, whether it is a public node, and where to probe it.
 *
 * <p>Deliberately narrower than {@link PdServiceDto}: this is the pull query's answer, and its
 * reader is reconciling containers and networks rather than editing the topology. It carries no
 * link ids, because a platform service in this list has none and the caller must not tell the two
 * kinds apart by their absence — {@code target} is what says which is which.
 */
public record PdLinkedServiceDto(
    String id,
    String name,
    PdDeploymentTarget target,
    boolean availableOnEnv,
    String healthPath) {}
