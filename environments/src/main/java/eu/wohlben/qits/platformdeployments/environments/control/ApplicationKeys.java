package eu.wohlben.qits.platformdeployments.environments.control;

/**
 * The id an application is addressed by on the read surface, derived rather than stored.
 *
 * <p>A service has one row, carrying N environment links, while a deployment row names only {@code
 * (application_name, environment_id)}. The client joins the two listings on an id, so the id has to
 * be computable from both sides: {@code <environmentId>:<name>}, and {@code platform:<name>} for a
 * service that belongs to no tier.
 *
 * <p>It is also the grouping key of the rollback pins: one service name in two environments is two
 * histories, and merging them would name the wrong rollback target.
 *
 * <p>The stand-in used to read {@code singleton:}; it reads {@code platform:} now, with the rest of
 * the vocabulary. Nothing persists it, so there is nothing to migrate — but a client that cached an
 * id across the rename would fail to join, which is why it is spelled once, here.
 */
public final class ApplicationKeys {

  /** Where a platform service's key stands in for an environment id — no environment can take the place. */
  private static final String PLATFORM = "platform";

  private ApplicationKeys() {}

  public static String of(String environmentId, String applicationName) {
    return (environmentId == null ? PLATFORM : environmentId) + ":" + applicationName;
  }
}
