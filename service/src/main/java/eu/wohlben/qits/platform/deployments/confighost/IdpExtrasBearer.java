package eu.wohlben.qits.platform.deployments.confighost;

import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The {@code configuration} named oidc client, and the reason this component holds one at all.
 *
 * <p>AGENTS.md records that {@code RegistryBearer} and the whole {@code quarkus-oidc-client} block
 * were deleted when the topology stopped being a peer — "if this service ever calls a guarded peer
 * again, all three arrive in that commit". qits-configuration is that peer: its read surface takes a
 * machine bearer for the {@code <env>-qits-configuration} audience, so the extension, the shipped-off
 * switch and the secret a deployment supplies are back, and they are back for exactly one caller.
 *
 * <p><b>The switch is the extension's own</b>, {@code
 * quarkus.oidc-client.configuration.client-enabled}, false in the shipped properties. There is no
 * key of ours beside it — the sibling arrangement in qits-workspaces, and one switch cannot
 * disagree with itself. Off, this answers empty and the read goes out anonymous; a platform running
 * qits-configuration behind forward-auth on its own network is a supported posture during the
 * migration.
 *
 * <p><b>A token this cannot mint is empty rather than an exception.</b> The refusal that matters
 * belongs to the read itself: an anonymous read of a guarded service comes back 401, and {@link
 * ConfigHostExtrasSource} refuses the deployment naming the url and the status. Throwing here would
 * report the same failure one layer earlier and less usefully.
 */
@ApplicationScoped
public class IdpExtrasBearer implements ExtrasBearer {

  private static final Logger LOG = Logger.getLogger(IdpExtrasBearer.class);

  /** The mint is not the read: this bounds the hop to idp, not the hop to qits-configuration. */
  private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(5);

  @ConfigProperty(name = "quarkus.oidc-client.configuration.client-enabled")
  boolean enabled;

  @Inject
  @NamedOidcClient("configuration")
  OidcClient oidcClient;

  /** Caches and refreshes the token, so one read per deployment is not one token request. */
  private final TokensHelper tokens = new TokensHelper();

  @Override
  public Optional<String> token() {
    if (!enabled) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(
              tokens.getTokens(oidcClient).await().atMost(TOKEN_TIMEOUT).getAccessToken())
          .filter(value -> !value.isBlank());
    } catch (RuntimeException e) {
      LOG.warnf("Could not get a machine token for qits-configuration: %s", e.toString());
      return Optional.empty();
    }
  }
}
