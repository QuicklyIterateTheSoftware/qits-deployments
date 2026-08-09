package eu.wohlben.qits.platform.deployments.deployments.control;

/**
 * The seam between this component's orchestration and the platform's postgres — the {@link
 * DeploymentDriver} arrangement for the third time: this module owns the interface and the state
 * machine that calls it, {@code service/pghost} owns the sole production implementation (plain
 * JDBC), and the suites install a scripted fake so a clone's {@code mvn verify} reaches no database
 * it did not start itself.
 *
 * <p><b>Three seams now, and they are the same rule applied three times.</b> Everything this
 * component cannot do inside its own process — shell out to docker, fetch a file over HTTP, speak
 * DDL to somebody else's server — is an interface here and an implementation over there.
 *
 * <p><b>It provisions and it never destroys.</b> There is no drop in this vocabulary and none is
 * coming: a database is the one thing a deployment can take away that a redeploy cannot give back.
 * Marking a resource obsolete is future work, and it will be a mark rather than a drop.
 *
 * <p><b>Idempotent by construction, because it is called on every deployment.</b> The caller does
 * not know what the server holds — the registry row and {@code pg_catalog} drift apart in both
 * directions, a reset postgres volume one way and a restored deployer database the other — so this
 * is handed both candidate passwords and answers which one is now in effect.
 */
public interface ResourceProvisioner {

  /** Make the role and the database exist, and say which password they ended up with. */
  Result ensure(Request request);

  /**
   * One resource to converge.
   *
   * <p>{@code storedPassword} is what the registry holds and <b>null when it holds no row</b>; the
   * pair with {@code freshPassword} is what makes the four drift cases decidable without a second
   * round trip. A role that exists while the registry has no row is a deployer database that was
   * reset, and the only way back is to rotate the role to the fresh value; a row that exists while
   * the role does not is a postgres volume that was reset, and the way back is to recreate the role
   * with the stored value so every running container keeps working.
   *
   * <p>The admin credential comes from deployment config — the trust domain that already holds the
   * docker socket — and is never stored in a row.
   */
  record Request(
      String host,
      int port,
      String adminUsername,
      String adminPassword,
      String databaseName,
      String roleName,
      String storedPassword,
      String freshPassword) {}

  /**
   * What the server ended up with. {@code passwordInEffect} is what the caller records and injects;
   * {@code detail} is the operator-facing sentence on failure and null on success.
   *
   * <p><b>Neither field may ever carry a password into a log.</b> {@code passwordInEffect} is a
   * value, not a message; {@code detail} is a message and must not name one.
   */
  record Result(boolean ok, String passwordInEffect, String detail) {}
}
