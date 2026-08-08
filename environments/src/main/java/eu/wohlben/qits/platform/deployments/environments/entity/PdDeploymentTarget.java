package eu.wohlben.qits.platform.deployments.environments.entity;

/**
 * Which plane a service runs on: once per environment, or once for the whole platform.
 *
 * <p>The distinction is not a size but a plane. An {@link #ENVIRONMENT} service is part of a tier —
 * each tier gets its own copy, isolated on that tier's networks — so it says which tiers it belongs
 * to by carrying a {@link PdServiceLink} to each. A {@link #PLATFORM} service is cross-environment:
 * one instance serves every environment and is reachable from every environment by design.
 *
 * <p><b>Both planes deploy off the same refs.</b> A green build deploys wherever an environment
 * listens to its branch — {@code environment/<name>} — and the plane decides only what is built out
 * of that: one instance per matching tier, or one for the platform. The platform plane had a deploy
 * ref of its own once ({@code platform/main}) and does not any more. {@code main} is the
 * integration trunk and deploys nothing.
 *
 * <p><b>A platform service carries no links, and that is the whole mechanism.</b> "Present
 * everywhere" is expressed as "linked nowhere in particular", which is what makes an environment
 * created tomorrow pick up qits-platform-idp without anyone editing a row. A stored link per
 * environment would be a set someone has to remember to extend.
 *
 * <p><b>The word used to be {@code singleton}.</b> It was wrong in the way that matters: it named a
 * cardinality when the thing being said is which plane the service lives on, and it made this very
 * component — cross-environment in behaviour from its first commit — look like an environment
 * citizen. {@code platform} is the vocabulary everywhere now: the enum, the docker label ({@code
 * qits.platform.deployments.target=platform}) and the network ({@code qits-platform}). The spec
 * parser still accepts {@code singleton} as an alias so a repository that has not been edited yet
 * keeps deploying; see {@code DeploymentSpecParser}.
 *
 * <p>A repository declares this in its {@code .config/qits/deployments.yml}; the deploy
 * orchestration derives the service row from it on every green build.
 */
public enum PdDeploymentTarget {

  /** One instance per environment, in the environment's networks. */
  ENVIRONMENT,

  /** One instance for the whole platform, in every environment's networks. */
  PLATFORM
}
