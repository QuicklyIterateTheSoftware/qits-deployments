package eu.wohlben.qits.platform.deployments.environments.error;

/** 404. */
public class NotFoundException extends PdException {

  public NotFoundException(String message) {
    super(404, message);
  }
}
