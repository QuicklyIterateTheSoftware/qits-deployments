package eu.wohlben.qits.platformdeployments.deployments.control;

/**
 * The repository's deployment spec could not be read or could not be understood.
 *
 * <p>Deliberately not one of the HTTP-mapped exceptions: this never answers a caller. It ends a
 * deployment — recorded {@code FAILED} with this message in its {@code detail} — because the
 * alternative is guessing a topology, and a guessed topology puts a container on the wrong networks
 * under the wrong name. A missing file is not this: no file means every default, and every
 * repository without one behaves exactly as it did before the file existed.
 */
public class SpecException extends RuntimeException {

  public SpecException(String message) {
    super(message);
  }

  public SpecException(String message, Throwable cause) {
    super(message, cause);
  }
}
