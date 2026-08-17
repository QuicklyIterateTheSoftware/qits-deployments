package eu.wohlben.qits.platform.deployments.events;

/**
 * One public path prefix of the immutable routing snapshot carried by {@link DeploymentActive}.
 * The deployer resolves {@code upstreamHost} from the deployment's wire alias: consumers never
 * reconstruct it from naming conventions and can proxy the event's exact deployment directly.
 *
 * <p>Navigation belongs only to the primary (first declared) route. A null label and position say
 * that this route is proxyable but does not create a navigation option.
 */
public record DeploymentEndpoint(
    String path,
    String upstreamHost,
    int upstreamPort,
    String navigationLabel,
    Integer navigationPosition) {}
