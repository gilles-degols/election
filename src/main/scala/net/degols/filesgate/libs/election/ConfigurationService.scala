package net.degols.filesgate.libs.election


import java.io.File

import com.google.inject.Inject
import com.typesafe.config.{Config, ConfigFactory}

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
  val akkaUri: String = s"akka://${ConfigurationService.ElectionSystemName}@$hostname:$port/user/${ConfigurationService.ElectionActorName}"

  override def toString: String = s"ElectionNode: $akkaUri"
}

object ConfigurationService {
  /**
    * Default election system name. We use a specific one for the election (different than the application one)
    */
  val ElectionSystemName: String = "ElectionSystem"

  /**
    * Default actor name to handle every remote message
    */
  val ElectionActorName: String = "ElectionActor"

  /**
    * Default port for the election nodes if those weren't given. 2181 is the one used by zookeeper, and we want to not
    * use it, so we will arbitrarily do "+1"
    */
  val DefaultPort: Int = 2182
}

/**
  * Created by Gilles.Degols on 03-09-18.
  */
class ConfigurationService @Inject()(config: Config) {
  lazy val fallbackConfig: Config = {
    val fileInSubproject = new File("../election/src/main/resources/application.conf")
    val fileInProject = new File("main/resources/application.conf")
    if (fileInSubproject.exists()) {
      ConfigFactory.load(ConfigFactory.parseFile(fileInSubproject))
    } else {
      ConfigFactory.load(ConfigFactory.parseFile(fileInProject))
    }
  }


  /**
    * List of nodes we have to watch / monitor. Those are needed to know if we have to watch 3 of them, or more, and to be able
    * to contact them. Try to not exceed 5.
    */
  val electionNodes: List[ElectionNode] = getStringList("election.nodes").map(node => ElectionNode(node))

  /**
    * How much time should we wait to reschedule a DiscoverNodes and send ping ? A low value (1 second) is recommended.
    * Ping and discovery is the same code.
    * This value must be smaller than the electionAttemptFrequency.
    */
  val discoverNodesFrequency: FiniteDuration = getInt("election.discover-nodes-frequency-ms") millis

  /**
    * How much time should we wait to resolve the actor of an unreachable node? The timeout should be small
    */
  val timeoutUnreachableNode: FiniteDuration = getInt("election.timeout-unreachable-node-s") millis

  /**
    * How much time should we wait for an ElectionAttempt without any success before retrying it? This should allow enough
    * time for all services to reply, and avoid the system being stuck.
    */
  val electionAttemptMaxFrequency: FiniteDuration = getInt("election.election-attempt-max-frequency-ms") millis
  val electionAttemptMinFrequency: FiniteDuration = getInt("election.election-attempt-max-frequency-ms") millis


  /**
    * Methods to get data from the embedded configuration, or the project configuration (it can override it)
    */
  private def getInt(path: String): Int = {
    Try{config.getInt(path)}.getOrElse(fallbackConfig.getInt(path))
  }

  private def getStringList(path: String): List[String] = {
    Try{config.getStringList(path)}.getOrElse(fallbackConfig.getStringList(path)).asScala.toList
  }
}