package net.degols.libs.election


import java.io.File

import com.google.inject.Inject
import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.LoggerFactory

import collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.Try


case class ElectionNode(rawPath: String) {
  // hostname or ip, same thing for the code
  val hostname: String = rawPath.split(':').head

  val port: Int = {
    val elements = rawPath.split(':')
    if(elements.length == 1) {
      ConfigurationService.DefaultPort
    } else {
      elements.last.toInt
    }
  }

  /**
    * URI to connect to the akka system on the election node.
    */
  val akkaUri: String = s"akka.tcp://${ConfigurationService.ElectionSystemName}@$hostname:$port/user/${ConfigurationService.ElectionActorName}"

  def jvmId: String = akkaUri.toString().replace(".tcp","").split("/user/").head

  override def toString: String = s"ElectionNode: $akkaUri"
}

object ConfigurationService {
  val DispatcherName: String = "ElectionDispatcher"

  /**
    * Default election system name. We use a specific one for the election (different than the application one)
    */
  val ElectionSystemName: String = "ElectionSystem"

  /**
    * Default actor name to handle every remote message
    */
  val ElectionActorName: String = "ElectionActor"

  /**
    * Default actor name to look for the leader (for the WatcherActor not taking part in the election)
    */
  val WatcherSystemName: String = "WatcherSystem"

  /**
    * Default port for the election nodes if those weren't given. 2181 is the one used by zookeeper, and we want to not
    * use it, so we will arbitrarily do "+1"
    */
  val DefaultPort: Int = 2182
}

/**
  * Created by Gilles.Degols on 03-09-18.
  */
class ConfigurationService @Inject()(val defaultConfig: Config) {
  private val logger = LoggerFactory.getLogger(getClass)
  /**
    * If the library is loaded directly as a subproject, the Config of the subproject overrides the configuration of the main
    * project by default, and we want the opposite.
    */
  private lazy val projectConfig: Config = {
    val projectFile = new File(pathToProjectFile)
    ConfigFactory.load(ConfigFactory.parseFile(projectFile))
  }

  private val pathToProjectFile: String = {
    Try{ConfigFactory.systemProperties().getString("config.resource")}.getOrElse("conf/application.conf")
  }

  private lazy val fallbackConfig: Config = {
    val fileInSubproject = new File("../election/src/main/resources/application.conf")
    val fileInProject = new File("main/resources/application.conf")
    if (fileInSubproject.exists()) {
      ConfigFactory.load(ConfigFactory.parseFile(fileInSubproject))
    } else {
      ConfigFactory.load(ConfigFactory.parseFile(fileInProject))
    }
  }
  val config = defaultConfig.withFallback(projectConfig).withFallback(fallbackConfig)

  /**
    * Configuration for the election system. We merge multiple configuration files: One embedded, the other one from the project
    * using the election library
    */
  val electionConfig: Config = config.getConfig("election")

  /**
    * List of nodes we have to watch / monitor. Those are needed to know if we have to watch 3 of them, or more, and to be able
    * to contact them. Try to not exceed 5. If none are given, localhost is used.
    */
  val electionNodes: List[ElectionNode] = {
    val rawNodes = getStringList("election.nodes")
    if(rawNodes.isEmpty) {
      logger.warn("No election node in the configuration, use 127.0.0.1 with the default port.")
      List(ElectionNode(s"127.0.0.1:${ConfigurationService.DefaultPort}"))
    } else {
      rawNodes.map(node => ElectionNode(node))
    }
  }

  /**
    * How much time should we wait to reschedule a DiscoverNodes and send ping ? A low value (1 second) is recommended.
    * Ping and discovery is the same code.
    * This value must be bigger than the timeoutUnreachableNode.
    */
  val discoverNodesFrequency: FiniteDuration = config.getInt("election.discover-nodes-frequency-ms") millis

  /**
    * How much time should we wait to resolve the actor of an unreachable node? The timeout should be small
    */
  val timeoutUnreachableNode: FiniteDuration = config.getInt("election.timeout-unreachable-node-ms") millis

  /**
    * Check heartbeat frequently, to see if we should switch to candidate and increase the term (or simply increase
    * the term if we are already in this mode)
    */
  val heartbeatCheckFrequency: FiniteDuration = config.getInt("election.heartbeat-check-frequency-ms") millis

  /**
    * We send Ping messages frequently
    */
  val heartbeatFrequency: FiniteDuration = config.getInt("election.heartbeat-frequency-ms") millis

  /**
    * How much time should we wait for an ElectionAttempt without any success before retrying it? This should allow enough
    * time for all services to reply, and avoid the system being stuck.
    */
  val electionAttemptMaxFrequency: FiniteDuration = config.getInt("election.election-attempt-max-frequency-ms") millis
  val electionAttemptMinFrequency: FiniteDuration = config.getInt("election.election-attempt-min-frequency-ms") millis


  /**
    * It's difficult to get a remote actor path locally. Because of that, we still want to know the current hostname + port
    */
  val akkaLocalHostname: String = config.getString("akka.remote.netty.tcp.hostname")
  val akkaLocalPort: Int = config.getInt("akka.remote.netty.tcp.port")

  val akkaElectionRemoteHostname: String = config.getString("election.akka.remote.netty.tcp.hostname")
  val akkaElectionRemotePort: Int = config.getInt("election.akka.remote.netty.tcp.port")

  /**
    * Methods to get data from the embedded configuration, or the project configuration (it can override it)
    */
  private def getStringList(path: String): List[String] = {
    config.getStringList(path).asScala.toList
  }
}