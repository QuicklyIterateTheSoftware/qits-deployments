package eu.wohlben.qits.platform.deployments.deployments.control;

/**
 * A resource a deployment declared could not be made to exist — the {@link SpecException} shape,
 * for the other half of what a repository declares.
 *
 * <p>Its message is written to be read on a deployment row by a person, so it says what was refused
 * and what to do about it. <b>It never carries a password</b>, which is the one thing that
 * separates a useful sentence here from a credential in a log.
 */
public class ResourceException extends RuntimeException {

  public ResourceException(String message) {
    super(message);
  }
}
