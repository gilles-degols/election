package net.degols.filesgate.libs.election


import com.google.inject.Inject
import com.typesafe.config.Config
import collection.JavaConverters._
import scala.concurrent.duration._


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
  /**
    * List of nodes we have to watch / monitor. Those are needed to know if we have to watch 3 of them, or more, and to be able
    * to contact them. Try to not exceed 5.
    */
  val electionNodes: List[ElectionNode] = config.getStringList("election.nodes").asScala.toList.map(node => ElectionNode(node))

  /**
    * How much time should we wait to reschedule a DiscoverNodes and send ping ? A low value (1 second) is recommended.
    * Ping and discovery is the same code.
    * This value must be smaller than the electionAttemptFrequency.
    */
  val discoverNodesFrequency: FiniteDuration = config.getInt("election.discover-nodes-frequency-s") seconds

  /**
    * How much time should we wait to resolve the actor of an unreachable node? The timeout should be small
    */
  val timeoutUnreachableNode: FiniteDuration = config.getInt("election.timeout-unreachable-node-s") seconds

  /**
    * How much time should we wait for an ElectionAttempt without any success before retrying it? This should allow enough
    * time for all services to reply, and avoid the system being stuck. Around 10 seconds should be enough.
    */
  val electionAttemptFrequency: FiniteDuration = config.getInt("election.election-attempt-frequency-s") seconds

  /**
    * Maximum time difference between two nodes. A correct system should always be synchronized with less than 100ms of
    * drift. Allowing more than a few seconds is asking for troubles. Don't forget that we only measure time when we are
    * processing a message, so it will be always be a bit bigger than the actual time difference
    */
  val maximumTimeDifferenceBetweenNodes: FiniteDuration = 5 seconds
}
