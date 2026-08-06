package eu.wohlben.qits.platform.deployments.environments.error;

/**
 * Base for this component's errors. Carries an HTTP-ish status code so the web layer can map it to
 * a response without either domain module depending on JAX-RS (the ci/artifacts stance). The
 * {@code service} module maps these via {@code PdExceptionMapper}.
 *
 * <p>It lives in the environments module because that is the one both domains can see — the
 * deployments module throws the same three subclasses rather than declaring a parallel hierarchy
 * whose only difference would be a package name.
 */
public class PdException extends RuntimeException {

  private final int statusCode;

  public PdException(int statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public PdException(int statusCode, String message, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }
}
