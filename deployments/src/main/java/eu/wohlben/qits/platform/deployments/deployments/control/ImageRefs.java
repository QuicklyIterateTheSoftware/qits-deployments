package eu.wohlben.qits.platform.deployments.deployments.control;

/**
 * The image-reference convention — the one place it is spelled: {@code
 * <registry-host>/<repository>/<application>:<sha>}, e.g. {@code
 * qits-artifacts:8080/qits/qits-gateway:4f2a91c...}.
 *
 * <p>Nothing in the build notification names an image; the reference is <em>derived</em> from the
 * application's name and the commit sha, which makes the tag convention a contract the publisher
 * has to meet rather than a value someone forgot to send. The registry host here is the one the
 * <b>docker daemon</b> resolves (it does the pulling), not one this process dials.
 */
public final class ImageRefs {

  private ImageRefs() {}

  public static String imageRef(
      String registryHost, String imageRepository, String applicationName, String sha) {
    return registryHost + "/" + imageRepository + "/" + applicationName + ":" + sha;
  }

  /**
   * Whether a reference the runtime reported back names this commit — the convention read the other
   * way round, which is how the startup sweep settles a row it did not finish itself.
   *
   * <p>The tag is what carries the sha, so it is compared whole: a reference tagged with another
   * commit is another deployment, never a near miss. The registry host may carry a port, so the
   * last colon is a tag separator only when it comes after the last slash, and a {@code @sha256:…}
   * digest an orchestrator resolved is dropped before the tag is read.
   */
  public static boolean carries(String imageRef, String sha) {
    if (imageRef == null || sha == null || sha.isBlank()) {
      return false;
    }
    String reference = imageRef.strip();
    int digest = reference.indexOf('@');
    if (digest >= 0) {
      reference = reference.substring(0, digest);
    }
    int colon = reference.lastIndexOf(':');
    if (colon < 0 || colon < reference.lastIndexOf('/')) {
      return false; // no tag at all: `latest`, and this component never deploys one
    }
    return reference.substring(colon + 1).equalsIgnoreCase(sha.strip());
  }
}
