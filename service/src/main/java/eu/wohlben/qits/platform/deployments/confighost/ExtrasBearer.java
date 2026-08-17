package eu.wohlben.qits.platform.deployments.confighost;

import java.util.Optional;

/**
 * The machine credential this component presents to qits-configuration, or none.
 *
 * <p><b>None is a supported answer and not a degraded one.</b> A platform may run qits-configuration
 * with forward-auth open on its own network during the transition, and then the read carries no
 * Authorization header at all — which is what the shipped state does, because {@code
 * quarkus.oidc-client.configuration.client-enabled} is false until a deployment turns it on. A
 * clone-alone build has no idp to mint anything against and must stay green.
 *
 * <p>It is a seam of its own rather than a call inside {@link ConfigHostExtrasSource} so that source
 * stays plain-JUnit testable: the suite scripts a bearer and asserts the header on a recorded
 * request, with no OIDC extension involved.
 */
@FunctionalInterface
public interface ExtrasBearer {

  /** The access token to present, or empty when this deployment holds no credential. */
  Optional<String> token();
}
