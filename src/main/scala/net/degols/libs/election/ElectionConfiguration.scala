package net.degols.libs.election


import java.io.File

import com.google.inject.Inject
import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.LoggerFactory

import collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.Try
import javax.inject.Singleton

// rawPath must be "actorSystem@hostname:port"
case class ElectionNode(rawPath: String) {
  // hostname or ip, same thing for the code
  val hostname: String = rawPath.split(':').head.split("@").last
  val actorSystemName: String = {
    val elems = rawPath.split('@')
    if(elems.length != 2) {
      throw new Exception("Invalid node configuration! Must be actorSystem@hostname:port")
    }
    elems.head
  }

  val port: Int = rawPath.split(':').last.toInt

  /**
    * URI to connect to the akka system on the election node.
    */
  val akkaUri: String = s"akka.tcp://$actorSystemName@$hostname:$port/user/${ElectionConfiguration.ElectionActorName}"

  def jvmId: String = akkaUri.toString().replace(".tcp","").split("/user/").head

  override def toString: String = s"ElectionNode: $akkaUri"
}

object ElectionConfiguration {
  val DispatcherName: String = "ElectionDispatcher"

  /**
    * Default actor name to handle every remote message
    */
  val ElectionActorName: String = "ElectionActor"

  /**
    * Default actor name to look for the leader (for the WatcherActor not taking part in the election)
    */
  val WatcherSystemName: String = "WatcherSystem"
}

/**
  * Created by Gilles.Degols on 03-09-18.
  */
@Singleton
class ElectionConfiguration @Inject()(val cfg: Config) extends ElectionConfigurationApi with ConfigurationMerge {

  /**
    * It's difficult to get a remote actor path locally. Because of that, we still want to know the current hostname + port
    */
  val akkaLocalHostname: String = config.getString("akka.remote.netty.tcp.hostname")
  val akkaLocalPort: Int = config.getInt("akka.remote.netty.tcp.port")

  /**
    * Configuration for the election system. We merge multiple configuration files: One embedded, the other one from the project
    * using the election library
    */
  val electionConfig: Config = config.getConfig("election")

  /**
    * List of nodes we have to watch / monitor. Those are needed to know if we have to watch 3 of them, or more, and to be able
    * to contact them. Try to not exceed 5. If none are given, localhost is used.
    */
  val electionNodes: Seq[ElectionNode] = {
    val rawNodes = getStringList("election.nodes")
    if(rawNodes.isEmpty) {
      logger.warn("No election node in the configuration, use the local system.")
      List(ElectionNode(s"$akkaLocalHostname:$akkaLocalPort"))
    } else {
      rawNodes.map(node => ElectionNode(node))
    }
  }

  /**
    * How much time should we wait to reschedule a DiscoverNodes and send ping ? A low value (1 second) is recommended.
    * Ping and discovery is the same code.
    * This value must be bigger than the timeoutUnreachableNode.
    */
  val discoverNodesFrequency: FiniteDuration = config.getInt("election.discover-nodes-frequency-ms").millis

  /**
    * How much time should we wait to resolve the actor of an unreachable node? The timeout should be small
    */
  val timeoutUnreachableNode: FiniteDuration = config.getInt("election.timeout-unreachable-node-ms").millis

  /**
    * Check heartbeat frequently, to see if we should switch to candidate and increase the term (or simply increase
    * the term if we are already in this mode)
    */
  val heartbeatCheckFrequency: FiniteDuration = config.getInt("election.heartbeat-check-frequency-ms").millis

  /**
    * We send Ping messages frequently
    */
  val heartbeatFrequency: FiniteDuration = config.getInt("election.heartbeat-frequency-ms").millis

  /**
    * How much time should we wait for an ElectionAttempt without any success before retrying it? This should allow enough
    * time for all services to reply, and avoid the system being stuck.
    */
  val electionAttemptMaxFrequency: FiniteDuration = config.getInt("election.election-attempt-max-frequency-ms").millis
  val electionAttemptMinFrequency: FiniteDuration = config.getInt("election.election-attempt-min-frequency-ms").millis


  /**
    * Methods to get data from the embedded configuration, or the project configuration (it can override it)
    */
  private def getStringList(path: String): Seq[String] = {
    config.getStringList(path).asScala.toList
  }

  override val directoryName: String = "election"
  override lazy val defaultConfig: Config = cfg
}