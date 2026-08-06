package eu.wohlben.qits.platformdeployments.environments.error;

/** 409. */
public class ConflictException extends PdException {

  public ConflictException(String message) {
    super(409, message);
  }
}
