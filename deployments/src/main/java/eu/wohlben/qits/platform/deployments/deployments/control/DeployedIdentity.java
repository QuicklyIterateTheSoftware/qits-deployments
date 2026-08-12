package eu.wohlben.qits.platform.deployments.deployments.control;

/**
 * What a deployed container is told about itself, in OpenTelemetry's own vocabulary — three
 * attributes, each a value this component genuinely holds at deploy time and none invented:
 *
 * <ul>
 *   <li>{@code service.version} — the deployment's commit sha. This component deploys sha-addressed
 *       images, so the sha IS the released identity; it is not a version number and is not dressed
 *       up as one.
 *   <li>{@code deployment.environment.name} — the environment this container belongs to, or {@value
 *       #PLATFORM_ENVIRONMENT} for a platform service, which belongs to all of them.
 *   <li>{@code service.instance.id} — the name this component assigned, which is unique per
 *       deployment and stable for the process' lifetime.
 * </ul>
 *
 * <p>Nothing else. In particular no {@code qits.workspace.id} or {@code qits.repository.id}: a
 * platform service has neither, and stamping a fake one to fit an old query model is what the
 * log-streaming plan forbids. {@code service.name} is left alone — each image sets it from its own
 * {@code quarkus.application.name}, which is what the observability source list buckets on.
 *
 * <p><b>Why two variables carry one value.</b> {@code OTEL_RESOURCE_ATTRIBUTES} is the
 * vendor-neutral spelling every OpenTelemetry SDK reads, and it is the contract; it is written
 * first and alone would be the whole of this class. But it does not win everywhere, and the one
 * place it loses is the one attribute that matters most here. Measured against the platform's
 * Quarkus 3.34.6, the SDK resource is assembled in this order (lowest precedence first):
 *
 * <ol>
 *   <li>the SDK's autoconfigured environment resource — where {@code OTEL_RESOURCE_ATTRIBUTES}
 *       lands;
 *   <li>Quarkus' own build-time attributes ({@code service.name}, {@code service.version} from the
 *       pom stamp, {@code webengine.*}), merged OVER the previous;
 *   <li>{@code quarkus.otel.resource.attributes} — i.e. {@code QUARKUS_OTEL_RESOURCE_ATTRIBUTES} —
 *       merged over everything.
 * </ol>
 *
 * <p>So the environment name and the instance id arrive from the neutral variable (Quarkus stamps
 * neither), while a {@code service.version} written only there is silently replaced by the pom
 * version baked into the image at build time — the stale identity this injection exists to correct.
 * The Quarkus-spelled variable is the layer that outranks the stamp. Both carry the same string,
 * built once here, so the two cannot disagree; a non-Quarkus image simply ignores the second name.
 *
 * <p><b>It is a class of its own because there are two drivers now</b>, and this is a belt as much
 * as a formatter: a comma or an equals sign in any of these values would forge an extra attribute,
 * so each is re-validated at the last line before an argv. Spelled twice, one copy would eventually
 * be loosened.
 */
public final class DeployedIdentity {

  /** What {@code deployment.environment.name} says for a container that is in every environment. */
  public static final String PLATFORM_ENVIRONMENT = "platform";

  /** The vendor-neutral variable every OpenTelemetry SDK reads. */
  public static final String OTEL_VARIABLE = "OTEL_RESOURCE_ATTRIBUTES";

  /** The Quarkus-spelled twin, which is the layer that outranks the image's own pom stamp. */
  public static final String QUARKUS_OTEL_VARIABLE = "QUARKUS_OTEL_RESOURCE_ATTRIBUTES";

  private DeployedIdentity() {}

  /**
   * The {@code k=v,k=v} list both variables carry.
   *
   * @param environmentName null for a platform service, which is told {@value
   *     #PLATFORM_ENVIRONMENT} instead — the one true thing there is to say
   * @param instanceName the container or service name this deployment assigned
   */
  public static String resourceAttributes(
      String commitSha, String environmentName, String instanceName) {
    // Belt at the argv, the health-path stance: each value is already validated at the boundary as
    // a sha or a dns label, and this is what makes loosening one of those checks a failed
    // deployment instead of a container stamped with attributes nobody wrote.
    String version = DeploymentIdentifiers.requireAttributeValue(commitSha, "commit sha");
    String environment =
        DeploymentIdentifiers.requireAttributeValue(
            environmentName == null ? PLATFORM_ENVIRONMENT : environmentName, "environment name");
    String instance = DeploymentIdentifiers.requireAttributeValue(instanceName, "container name");
    return "service.version="
        + version
        + ",deployment.environment.name="
        + environment
        + ",service.instance.id="
        + instance;
  }
}
