package eu.wohlben.qits.platform.deployments;

import eu.wohlben.qits.archrules.DatasourceBaselineRules;
import org.junit.jupiter.api.Test;

/**
 * Every postgresql datasource this deployable declares carries the platform's resilience baseline:
 * the patient driver, validation at borrow, and a 15s acquisition timeout. The rule reads the
 * config rather than the code, and it names each missing line.
 *
 * <p><b>It lives in {@code service/} for the reason {@code ArchRulesTest} does</b>: this is the only
 * module whose config is the deployable's whole config. The component's own {@code
 * platformdeployments} datasource is declared in the {@code environments} jar and the bus client's
 * {@code eventstream} one in qits-eventstream, so a copy in either domain module would judge half of
 * what actually boots.
 *
 * <p>A cutover of qits-oci-postgresql — which this component performs itself — is what the baseline
 * exists for; {@code docs/project-setup-quinoa-angular.md} has the measurements.
 */
class DatasourceBaselineTest {

  @Test
  void everyPostgresDatasourceCarriesTheBaseline() {
    DatasourceBaselineRules.assertBaseline();
  }
}
