package eu.wohlben.qits.platform.deployments.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.platform.deployments.bus.PdBuildSuccessfulSubscriber.BuildSuccessfulPayload;
import org.junit.jupiter.api.Test;

/**
 * The decode, on its own: a {@code BuildSuccessful} payload as qits-ci canonicalizes it, read back
 * into the four fields this component acts on.
 *
 * <p>Plain JUnit, because binding is the library mapper's job and needs no application. It is
 * separate from the flow test for the reason every cross-repo contract here is pinned somewhere: a
 * field renamed in qits-ci's event class is silent on this side — nothing imports it — and this is
 * the assertion that would notice, given a payload copied from the other repository.
 */
public class PdBuildSuccessfulPayloadTest {

  private static final String SHA = "a".repeat(40);

  /** Byte-for-byte what qits-ci publishes: keys sorted, no whitespace, absent fields omitted. */
  private static final String PAYLOAD =
      "{\"branch\":\"environment/prod\",\"commitSha\":\""
          + SHA
          + "\",\"finishedAt\":\"2026-08-10T10:32:02Z\",\"repoId\":\"qits-gateway\","
          + "\"runId\":\"3f1d0c2e-77aa-4b0e-9a5b-2b1c0d9e4f88\"}";

  @Test
  public void thePayloadReadsBackAsTheTripleAndTheRunId() {
    BuildSuccessfulPayload build = CanonicalJson.payloadTo(PAYLOAD, BuildSuccessfulPayload.class);

    assertEquals("qits-gateway", build.repoId());
    assertEquals("environment/prod", build.branch());
    assertEquals(SHA, build.commitSha());
    assertEquals("3f1d0c2e-77aa-4b0e-9a5b-2b1c0d9e4f88", build.runId());
  }

  @Test
  public void theNameFieldsBindWhenThePublisherSendsThem() {
    // Post-rollback qits-ci fills the repository's public address from the push route. repoId is
    // the opaque storage key, and the NAME is what the deployment is named after.
    BuildSuccessfulPayload build =
        CanonicalJson.payloadTo(
            "{\"branch\":\"environment/dev\",\"commitSha\":\""
                + SHA
                + "\",\"projectId\":\"qits\","
                + "\"repoId\":\"6d0c2b1e-3a44-4b0e-9a5b-2b1c0d9e4f88\","
                + "\"repoName\":\"qits-gateway\"}",
            BuildSuccessfulPayload.class);

    assertEquals("6d0c2b1e-3a44-4b0e-9a5b-2b1c0d9e4f88", build.repoId());
    assertEquals("qits", build.projectId());
    assertEquals("qits-gateway", build.repoName());
  }

  @Test
  public void anEventPublishedBeforeTheNameFieldsExistedBindsThemNull() {
    // The compatibility arm: a replayed old event names no project and no repository, and the
    // announcement falls back to the repoId — which before the rollback WAS the name.
    BuildSuccessfulPayload build = CanonicalJson.payloadTo(PAYLOAD, BuildSuccessfulPayload.class);

    assertNull(build.projectId());
    assertNull(build.repoName());
  }

  @Test
  public void fieldsThisComponentDoesNotBindAreIgnoredRatherThanFatal() {
    // qits-ci's event carries imageDigest and finishedAt as well, and will carry a seventh field
    // one day. An arriving payload with more in it than this record names must not fail: the
    // library's mapper has FAIL_ON_UNKNOWN_PROPERTIES off precisely so a newer publisher does not
    // stop an older consumer.
    BuildSuccessfulPayload build =
        CanonicalJson.payloadTo(
            "{\"repoId\":\"qits-ci\",\"branch\":\"main\",\"commitSha\":\""
                + SHA
                + "\",\"imageDigest\":\"sha256:abc\",\"somethingNew\":42}",
            BuildSuccessfulPayload.class);

    assertEquals("qits-ci", build.repoId());
    assertNull(build.runId(), "an absent field is null, and a run id is optional anyway");
  }
}
