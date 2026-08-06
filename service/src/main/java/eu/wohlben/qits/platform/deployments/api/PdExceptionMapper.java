package eu.wohlben.qits.platform.deployments.api;

import eu.wohlben.qits.platform.deployments.environments.error.PdException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/**
 * Maps the domains' framework-free {@link PdException}s (each carrying a status code) to HTTP
 * responses — kept here in {@code service} because neither domain module carries JAX-RS.
 *
 * <p>The envelope is the platform's: {@code {"message": "..."}}, one key, the sentence the domain
 * threw. The refused plane flip's message names its own remediation, so a caller's log is where the
 * fix is read.
 */
@Provider
public class PdExceptionMapper implements ExceptionMapper<PdException> {

  @Override
  public Response toResponse(PdException exception) {
    int status = exception.statusCode();
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      message = Response.Status.fromStatusCode(status).getReasonPhrase();
    }
    return Response.status(status)
        .entity(Map.of("message", message))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }
}
