package eu.wohlben.qits.platform.deployments.deployments.entity;

/**
 * A deployment's lifecycle. {@code QUEUED} and {@code STARTING} are the only non-terminal states,
 * and neither survives a restart (the worker queue is in-memory; the startup sweep settles them).
 *
 * <p><b>{@code FAILED} used to be five different outcomes.</b> A row that said it could mean the
 * apply was refused, the successor never converged and the orchestrator put the predecessor back, a
 * restart interrupted the row and a newer deployment took its place, or a container that had been
 * serving for hours was found gone — four questions with four different answers ("is anything
 * serving?", "does somebody have to act?") flattened into one word. So three of them are their own
 * words now: {@link #ROLLED_BACK}, {@link #SUPERSEDED} and {@link #GONE}. Nothing was removed and no
 * row was relabelled — the column is a varchar with no check constraint precisely so a vocabulary
 * can grow, and every historical {@code FAILED} still says what it said. {@code FAILED} keeps the
 * remainder, narrowed to its honest meaning: the attempt ended and nothing is known to serve.
 *
 * <p><b>The terminal states are terminal but not final.</b> {@code ACTIVE}, {@code FAILED} and
 * {@code GONE} are the ones a container's own state can contradict, so the periodic observation
 * ({@code DeploymentObserver}) settles the disagreement on the LATEST row of each (application,
 * tier): a {@code FAILED} or {@code GONE} row whose own container is running and healthy becomes
 * {@code ACTIVE}, and an {@code ACTIVE} row whose container is absent or terminally exited on two
 * consecutive passes becomes {@code GONE}. The rest are nobody's to observe — {@code QUEUED} and
 * {@code STARTING} belong to the worker's state machine, {@code IMAGE_MISSING} is a statement about
 * a registry rather than a container, {@code SUPERSEDED} is a statement about a row a later
 * deployment overtook, and {@code DECOMMISSIONED} is a decision another deployment made. A row that
 * is not the latest for its place is history and is never revisited.
 */
public enum PdDeploymentStatus {
  /** Recorded by the intake, waiting for the single-threaded deploy worker. */
  QUEUED,
  /** The worker is pulling the image, starting the container, or waiting on the health gate. */
  STARTING,
  /**
   * Passed the health gate; its container serves the application on its networks. Also what the
   * observation writes onto a {@code FAILED} or {@code GONE} row whose container turns out to be
   * running and healthy — the detail then carries the recovery stamp with the original failure text
   * under it.
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
   * The attempt ended and <b>nothing is known to serve the place</b>. The apply was refused, the
   * convergence failed without the orchestrator reverting anything, or a restart interrupted the row
   * with no evidence of what took over.
   *
   * <p>It is the narrowed word, and the narrowing is what makes it worth paging on: the three
   * outcomes below all leave somebody serving or say who overtook the row, and each of them used to
   * arrive here.
   */
  FAILED,
  /**
   * The successor never converged and the orchestrator <b>put the predecessor back</b>, which is
   * still serving. Swarm's {@code rollback_completed} / {@code rollback_paused}, reaching this
   * component as {@code DeploymentDriver.ConvergenceOutcome#ROLLED_BACK}.
   *
   * <p>Distinct from {@code FAILED} because the platform is not down: the deployment did not land,
   * and the application it targeted is exactly as available as it was a minute earlier. What it asks
   * for is a fix to the commit, not a page.
   */
  ROLLED_BACK,
  /**
   * An in-flight row a restart interrupted, whose place a <b>newer deployment took</b>. The startup
   * sweep writes it when the service running under the row's name carries a different sha: this
   * attempt's outcome is unknowable and no longer interesting, because something later is serving.
   *
   * <p>An interrupted row with no such evidence stays {@code FAILED} — nothing is known to serve
   * there, which is the whole difference between the two words.
   */
  SUPERSEDED,
  /**
   * Was {@code ACTIVE}, and the observation found its container <b>absent or terminally exited</b>
   * on two consecutive passes. The deployment itself succeeded; the place died afterwards.
   *
   * <p>Distinct from {@code FAILED} because it indicts nothing about the build or the commit — the
   * gate passed, and this row is the only place a platform learns that what passed it is no longer
   * running. It is a demotion and it self-heals: a container that comes back healthy takes the row
   * back to {@code ACTIVE}, the same recovery a {@code FAILED} row gets.
   */
  GONE,
  /** Was ACTIVE; replaced by a newer deployment that passed the health gate. */
  DECOMMISSIONED
}
