package eu.wohlben.qits.platformdeployments.environments.error;

/** 400. */
public class BadRequestException extends PdException {

  public BadRequestException(String message) {
    super(400, message);
  }
}
