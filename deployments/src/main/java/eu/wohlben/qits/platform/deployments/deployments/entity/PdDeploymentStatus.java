package eu.wohlben.qits.platform.deployments.deployments.entity;

/**
 * A deployment's lifecycle. {@code QUEUED} and {@code STARTING} are the only non-terminal states,
 * and neither survives a restart (the worker queue is in-memory; the startup sweep fails them).
 *
 * <p><b>{@code ACTIVE} and {@code FAILED} are terminal but no longer final.</b> They are the two
 * states a container's own state can contradict, so the periodic observation
 * ({@code DeploymentObserver}) settles the disagreement on the LATEST row of each (application,
 * tier): a {@code FAILED} row whose own container is running and healthy becomes {@code ACTIVE}, and
 * an {@code ACTIVE} row whose container is absent or terminally exited on two consecutive passes
 * becomes {@code FAILED}. The other four states are nobody's to observe — {@code QUEUED} and {@code
 * STARTING} belong to the worker's state machine, {@code IMAGE_MISSING} is a statement about a
 * registry rather than a container, and {@code DECOMMISSIONED} is a decision another deployment made.
 * A row that is not the latest for its place is history and is never revisited.
 */
public enum PdDeploymentStatus {
  /** Recorded by the intake, waiting for the single-threaded deploy worker. */
  QUEUED,
  /** The worker is pulling the image, starting the container, or waiting on the health gate. */
  STARTING,
  /**
   * Passed the health gate; its container serves the application on its networks. Also what the
   * observation writes onto a {@code FAILED} row whose container turns out to be running and healthy
   * — the detail then carries the recovery stamp with the original failure text under it.
   */
  ACTIVE,
  /**
   * The OCI registry has no image for this (application, sha) — the honest name for "CI went green
   * but no image arrived", which stays a distinct state because it indicts the publishing
   * convention rather than the build. Publishing is a repository's own last pipeline step, so this
   * state means that pipeline publishes nothing or its tag broke the convention.
   */
  IMAGE_MISSING,
  /**
   * Docker refused, the container died, or the health gate expired. The old container stays. Also
   * what the observation writes onto an {@code ACTIVE} row whose container two consecutive passes
   * found absent or terminally exited — with what it observed, and when, on the detail.
   */
  FAILED,
  /** Was ACTIVE; replaced by a newer deployment that passed the health gate. */
  DECOMMISSIONED
}
