package net.degols.filesgate.libs.election

import javax.inject.Inject

import akka.actor.{ActorContext, ActorRef}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, TimeoutException}
import scala.util.{Failure, Success, Try}

/**
  * Created by Gilles.Degols on 27-08-18.
  */
@Singleton
class ElectionService @Inject()(configurationService: ConfigurationService) {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
    * Must be set by the ElectionActor
    */
  var context: ActorContext = _

  /**
    * ActorRef for each Jvm
    */
  var jvmActorRefs: Map[String, ActorRef] = Map.empty

  /**
    * Latest detected leader
    */
  var lastLeader: Option[ActorRef] = None

  /**
    * History of every JVM, based on the jvm id
    */
  private var _jvm_histories: Map[String, JVMHistory] = Map.empty

  /**
    * Every time we receive a jvm message, we add it to the history. The watcher is node on another level.
    * @param remoteMessage
    */
  def addJvmMessage(remoteMessage: RemoteMessage): Unit = {
    val jvmHistory = _jvm_histories.get(remoteMessage.jvmId) match {
      case Some(history) => history
      case None =>
        _jvm_histories = _jvm_histories ++ Map(remoteMessage.jvmId -> new JVMHistory(remoteMessage.jvmId))
        _jvm_histories(remoteMessage.jvmId)
    }

    jvmHistory.addMessage(remoteMessage)
  }


  /**
    * Add monitoring to a jvm, if it does not exist yet
    * @param jvmId
    * @param actorRef
    */
  def watchJvm(jvmId: String, actorRef: ActorRef): Unit = {
    jvmActorRefs.get(jvmId) match {
      case None =>
        // The watcher could fail if it is not available anymore
        Try{context.watch(actorRef)} match {
          case Success(res) =>
            jvmActorRefs = jvmActorRefs ++ Map(jvmId -> actorRef)
          case Failure(err) =>
            logger.warn("Failed to put a watcher on a jvm, it probably died during the process.")
        }

      case Some(res) =>
      // Nothing to do, already watching
    }
  }

  /**
    * Remove the watching of a jvm, typically used when we received a Terminated message
    */
  def unwatchJvm(actorRef: ActorRef): Unit = {
    jvmIdForActorRef(actorRef) match {
      case Some(res) => jvmActorRefs = jvmActorRefs.filter(_._1 != res)
      case None => logger.error(s"Trying to unwatch a jvm ($actorRef) already not watched anymore...")
    }
  }

  /**
    * Send a ping to every actor, reachable or not.
    */
  def sendPingToAllNodes(): Unit = {
    configurationService.electionNodes.foreach(electionNode => {
      actorRefForElectionNode(electionNode) match {
        case Some(res) => // Quite simple to contact it
          logger.debug(s"Send ping to reachable ElectionNode: $electionNode")
          res ! Ping(context.self)
        case None => // Need to resolve the actor path. It might not exist, we are not sure
          logger.debug(s"Send ping to previously unreachable ElectionNode: $electionNode")
          sendPingToUnreachableNode(electionNode)
      }
    })
  }

  /**
    * Try to send a ping to an unreachable node. To avoid locking the system, this is asynchronous.
    * @param electionNode
    */
  private def sendPingToUnreachableNode(electionNode: ElectionNode): Future[Try[Unit]] = {
    val remoteActorRef: ActorRef = try {
      Await.result(context.actorSelection(electionNode.akkaUri).resolveOne(configurationService.timeoutUnreachableNode).recover {
        case t: TimeoutException =>
          logger.warn("Got a TimeoutException while trying to resolve the actorRef of an ElectionNode.")
          null
        case x: Exception =>
          logger.warn("Got a generic Exception while trying to resolve the actorRef of an ElectionNode.")
          x.printStackTrace()
          null
      }, configurationService.timeoutUnreachableNode)
    } catch {
      case x: TimeoutException =>
        logger.warn("Got a TimeoutException while trying to resolve the actorRef of an ElectionNode.")
        null
      case x: Throwable =>
        logger.warn("Got a generic Exception while trying to resolve the actorRef of an ElectionNode.")
        x.printStackTrace()
        null
    }

    if(remoteActorRef != null) {
      remoteActorRef ! Ping(context.self)
    }
  }

  /**
    * Indicate if we have a majority of nodes running
    */
  def enoughReachableNodes: Boolean = {
    jvmActorRefs.values.size > configurationService.electionNodes.length / 2f
  }

  /**
    * Can we reach all nodes in the configuration?
    * @return
    */
  def allReachableNodes: Boolean = {
    jvmActorRefs.values.size == configurationService.electionNodes.length
  }

  /**
    * Try to find the jvm id for a given actor ref. Normally we should always have it.
    */
  def jvmIdForActorRef(actorRef: ActorRef): Option[String] = {
    jvmActorRefs.find(_._2.toString() == actorRef.toString()) match {
      case Some(res) => Option(res._1)
      case None => None
    }
  }

  /**
    * Return the actor ref for a given ElectionNode, if it exists.
    */
  def actorRefForElectionNode(electionNode: ElectionNode): Option[ActorRef] = {
    jvmActorRefs.find(_._2.toString() == electionNode.akkaUri) match {
      case Some(res) => Option(res._2)
      case None => None
    }
  }

  /**
    * We only allow messages from machines with an ip / hostname in the configuration
    */
  def actorRefInConfig(actorRef: ActorRef): Boolean = {
    val isInConfig = configurationService.electionNodes.exists(electionNode => {
      logger.debug(s"Comparing election node with actor ref: ${electionNode.akkaUri} vs ${actorRef.path.toString}")
      electionNode.akkaUri == actorRef.path.toString
    })

    if(!isInConfig) {
      logger.warn(s"We received a message from an actorRef (${actorRef.path.toString} not in the configured nodes: ${configurationService.electionNodes}")
    }

    isInConfig
  }

}
