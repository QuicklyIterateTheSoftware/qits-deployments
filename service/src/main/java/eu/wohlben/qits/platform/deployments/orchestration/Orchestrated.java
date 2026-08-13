package eu.wohlben.qits.platform.deployments.orchestration;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.inject.Qualifier;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Which orchestrator a {@code DeploymentDriver} implementation speaks for.
 *
 * <p>It exists to keep the two implementations <b>out of ordinary injection</b>. A qualifier other
 * than {@code @Default} is what makes {@code @Inject DeploymentDriver} resolve to exactly one bean
 * — {@link DeploymentDrivers}' producer, which reads the config key and picks — instead of failing
 * the build as an ambiguous dependency. Nothing injects a driver by this qualifier except that
 * producer, and nothing should: choosing an orchestrator per injection point is the decision the
 * key exists to make once.
 */
@Qualifier
@Retention(RUNTIME)
@Target({TYPE, FIELD, METHOD, PARAMETER})
public @interface Orchestrated {

  Kind value();

  enum Kind {
    DOCKER,
    SWARM
  }
}
