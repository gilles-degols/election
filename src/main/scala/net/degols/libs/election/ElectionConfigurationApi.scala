package net.degols.libs.election

import com.google.inject.ImplementedBy
import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration

@ImplementedBy(classOf[ElectionConfiguration])
trait ElectionConfigurationApi {
  val akkaLocalHostname: String
  val akkaLocalPort: Int

  /**
    * Configuration for the election system. We merge multiple configuration files: One embedded, the other one from the project
    * using the election library
    */
  val electionConfig: Config

  /**
    * List of nodes we have to watch / monitor. Those are needed to know if we have to watch 3 of them, or more, and to be able
    * to contact them. Try to not exceed 5. If none are given, localhost is used.
    */
  val electionNodes: Seq[ElectionNode]

  /**
    * How much time should we wait to reschedule a DiscoverNodes and send ping ? A low value (1 second) is recommended.
    * Ping and discovery is the same code.
    * This value must be bigger than the timeoutUnreachableNode.
    */
  val discoverNodesFrequency: FiniteDuration

  /**
    * How much time should we wait to resolve the actor of an unreachable node? The timeout should be small
    */
  val timeoutUnreachableNode: FiniteDuration

  /**
    * Check heartbeat frequently, to see if we should switch to candidate and increase the term (or simply increase
    * the term if we are already in this mode)
    */
  val heartbeatCheckFrequency: FiniteDuration

  /**
    * We send Ping messages frequently
    */
  val heartbeatFrequency: FiniteDuration

  /**
    * How much time should we wait for an ElectionAttempt without any success before retrying it? This should allow enough
    * time for all services to reply, and avoid the system being stuck.
    */
  val electionAttemptMaxFrequency: FiniteDuration
  val electionAttemptMinFrequency: FiniteDuration
}
