package net.degols.libs.election

import javax.inject.{Inject, Singleton}

import akka.actor.{ActorContext, ActorRef}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, TimeoutException}
import scala.util.{Failure, Success, Try, Random}
import scala.concurrent.duration._

/**
  * Election system is using the Raft algorithm: https://www.usenix.org/system/files/conference/atc14/atc14-paper-ongaro.pdf
  */
@Singleton
class ElectionService @Inject()(electionConfigurationService: ElectionConfigurationApi) {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
    * Every time we start a new term, we increment it.
    */
  private var _termNumber: Long = 0L

  /**
    * If we replied to a specific term (from another jvm)
    */
  private var _lastRepliedRequestVotes: Option[RequestVotes] = None

  /**
    * Last election seed that we sent
    */
  private var _lastRequestVotes: Option[RequestVotes] = None

  /**
    * RequestVotes received from external nodes.
    */
  private var _otherRequestVotes: Seq[RequestVotes] = List.empty[RequestVotes]

  /**
    * List of accepted answers for the _lastRequestVotes.
    */
  private var _requestVotesReplies: Seq[RequestVotesReplyWrapper] = List.empty[RequestVotesReplyWrapper]

  /**
    * Must be set by the ElectionActor
    */
  var context: ActorContext = _

  /**
    * Must be set by the ElectionActor
    */
  var actorRefWrapper: ActorRef = _

  /**
    * ActorRef for each Jvm
    */
  var jvmActorRefs: Map[String, ActorRef] = Map.empty

  /**
    * Latest detected leader
    */
  var lastLeader: Option[ActorRef] = None

  /**
    * Latest detected leader wrapper (linked to the leader)
    */
  var lastLeaderWrapper: Option[ActorRef] = None

  /**
    * History of every JVM, based on the jvm id
    */
  private var _jvm_histories: Map[String, JVMHistory] = Map.empty

  def increaseTermNumber(termNumber: Option[Long]): Unit = if(termNumber.isEmpty) _termNumber += 1 else _termNumber = termNumber.get

  def termNumber: Long = _termNumber

  def lastRepliedRequestVotes: Option[RequestVotes] = _lastRepliedRequestVotes
  def resetLastRepliedRequestVotes(): Unit = _lastRepliedRequestVotes = None

  /**
    * Every time we receive a jvm message, we add it to the history. The watcher is node on another level.
    * @param remoteMessage
    */
  def addJvmMessage(remoteMessage: RemoteMessage): Unit = {
    val wrapper = new RemoteMessageWrapper(remoteMessage)
    val jvmHistory = _jvm_histories.get(remoteMessage.jvmId) match {
      case Some(history) => history
      case None =>
        _jvm_histories = _jvm_histories ++ Map(remoteMessage.jvmId -> new JVMHistory(remoteMessage.jvmId))
        _jvm_histories(remoteMessage.jvmId)
    }

    jvmHistory.addMessage(wrapper)
  }

  /**
    * Retrieve the leader from the Ping messages. We only accept a leader from a ping of the leader itself, and only
    * if the last ping message is recent enough
    */
  def leaderFromPings: Option[ActorRef] = {
    if(enoughReachableNodes) {
      val distinctLeaders = jvmActorRefs.flatMap(jvm => {
        _jvm_histories.get(jvm._1) match {
          case Some(jvmHistory) => jvmHistory.lastPingWrapper()
          case None => None
        }
      }).filter(ping => {
        // Only keep ping if recent enough
        ping.creationDatetime.getMillis + 5* electionConfigurationService.heartbeatFrequency.toMillis >= ElectionTools.datetime().getMillis
      }).map(_.remoteMessage.asInstanceOf[Ping])
        .filter(ping => ping.leaderActorRef.isDefined && ping.leaderActorRef.get == ping.actorRef) // Only keep the leader from the leader itself
        .map(_.leaderActorRef.get).toList.distinct

      if(distinctLeaders.isEmpty) {
        logger.debug("No leader received from external Ping messages, wait for the election.")
        val tmp = jvmActorRefs.flatMap(jvm => {
          _jvm_histories.get(jvm._1) match
          {
            case Some(jvmHistory) => jvmHistory.lastPingWrapper()
            case None => None
          }
        })

        None
      } else if(distinctLeaders.length == 1){
        logger.debug(s"Got a leader from the external nodes: ${distinctLeaders.head}")
        distinctLeaders.headOption
      } else {
        logger.error(s"We received Ping messages from some JVM giving a different last leader ($distinctLeaders). We cannot choose one for the moment.")
        None
      }
    } else {
      None
    }
  }

  /**
    * Add monitoring to a jvm, if it does not exist yet
    * @param jvmId
    * @param actorRef
    */
  def watchJvm(jvmId: String, actorRef: ActorRef): Unit = {
    jvmActorRefs.get(jvmId) match {
      case None =>
        logger.debug(s"Start to watch $jvmId / $actorRef. Current topology: $jvmActorRefs")
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
    logger.debug(s"Unwatch $actorRef")
    jvmIdForActorRef(actorRef) match {
      case Some(res) => {
        jvmActorRefs = jvmActorRefs.filter(_._1 != res)
      }
      case None => logger.error(s"Trying to unwatch a jvm ($actorRef) already not watched anymore...")
    }
  }

  /**
    * Send a ping to every actor-ref, they must be reachable first, we don't want to resolve an actor ref here.
    */
  def sendPingToKnownNodes(): Unit = {
    electionConfigurationService.electionNodes.foreach(electionNode => {
      actorRefForElectionNode(electionNode) match {
        case Some(res) => // Quite simple to contact it
          logger.debug(s"Send ping to reachable ElectionNode: $electionNode")
          res.tell(Ping(context.self, lastLeader, _termNumber), context.self)
        case None => // Need to resolve the actor path. It might not exist, we are not sure
          logger.debug(s"Do not send ping to previously unreachable ElectionNode: $electionNode")
      }
    })
  }

  def sendPingToUnreachableNodes(): Unit = {
    electionConfigurationService.electionNodes.foreach(electionNode => {
      actorRefForElectionNode(electionNode) match {
        case Some(res) => // Nothing to do here
        case None => // Need to resolve the actor path. It might not exist, we are not sure
          logger.debug(s"Send ping to previously unreachable ElectionNode: $electionNode")
          val ping = Ping(context.self, lastLeader, _termNumber)
          sendMessageToUnreachableNode(electionNode, ping)
      }
    })
  }

  /**
    * Specific method for the WatcherActor (only!), when it wants to find the current leader. For that, we select
    * one random node.
    * TODO: We should rather try to contact a majority of nodes to find the leader. If we don't have a majority we must stop the instance
    */
  def sendWhoIsTheLeader(): Unit = {
    val message = WhoIsTheLeader()
    val electionNode = Random.shuffle(electionConfigurationService.electionNodes).head
    actorRefForElectionNode(electionNode) match {
      case Some(res) => // Quite simple to contact it
        logger.debug(s"Send WhoIsTheLeader to reachable ElectionNode: $electionNode")
        res.tell(Ping(context.self, lastLeader, _termNumber), context.self)
      case None => // Need to resolve the actor path. It might not exist, we are not sure
        logger.debug(s"Send WhoIsTheLeader to previously unreachable ElectionNode: $electionNode")
        sendMessageToUnreachableNode(electionNode, message)
    }
  }

  /**
    * Try to send a message to an unreachable node. To avoid locking the system, this is asynchronous.
    * @param electionNode
    */
  private def sendMessageToUnreachableNode(electionNode: ElectionNode, message: Any): Future[Try[Unit]] = Future {
    Try {
      val remoteActorRef: ActorRef = try {
        Await.result(context.actorSelection(electionNode.akkaUri).resolveOne(electionConfigurationService.timeoutUnreachableNode).recover {
          case t: TimeoutException =>
            logger.warn("Got a TimeoutException while trying to resolve the actorRef of an ElectionNode.")
            null
          case x: Exception =>
            logger.warn(s"Got a generic Exception while trying to resolve the actorRef of an ElectionNode: ${x.getMessage}.")
            //x.printStackTrace()
            null
        }, electionConfigurationService.timeoutUnreachableNode)
      } catch {
        case x: TimeoutException =>
          logger.warn("Got a TimeoutException while trying to resolve the actorRef of an ElectionNode.")
          null
        case x: Throwable =>
          logger.warn(s"Got a generic Exception while trying to resolve the actorRef of an ElectionNode: ${x.getMessage}.")
          //x.printStackTrace()
          null
      }

      if(remoteActorRef != null) {
        logger.debug(s"Send message $message to an actorRef from an unreachable node: $electionNode.")
        remoteActorRef.tell(message, context.self)
      }
    }
  }

  /**
    * Attempt an election: it sends an ElectionSeed message to every reachable node. If we receive replies fast enough acknowledging
    * it, the current node will be elected.
    */
  def sendRequestVotes(): Unit = {
    // Generate an election seed if we don't have one yet.
    generateElectionSeed()

    logger.debug(s"Election nodes: ${electionConfigurationService.electionNodes}")
    electionConfigurationService.electionNodes.foreach(electionNode => {
      actorRefForElectionNode(electionNode) match {
        case Some(res) => // Quite simple to contact it
          logger.debug(s"Send RequestVotes (${_lastRequestVotes.get}) to reachable ElectionNode: $electionNode")
          res.tell(_lastRequestVotes.get, context.self)
        case None => // Unreachabled node, we still try to send the message
          logger.warn(s"Send RequestVotes (${_lastRequestVotes.get}) to unreachable ElectionNode: $electionNode")
          // TODO: Better solution than a Await
          Try{Await.result(sendMessageToUnreachableNode(electionNode, _lastRequestVotes.get), 1 second)}
      }
    })
  }

  /**
    * Generate an election seed, but do not send it directly
    */
  private def generateElectionSeed(): Unit = {
    _termNumber += 1L
    _lastRequestVotes = Option(RequestVotes(context.self, _termNumber))
    _requestVotesReplies = List.empty[RequestVotesReplyWrapper]
    _otherRequestVotes = List.empty[RequestVotes]
  }

  /**
    * Give the reply for a given RequestVotes message.
    */
  def replyToRequestVotes(requestVotes: RequestVotes): Either[RequestVotesAccepted, RequestVotesRefused] = {
    // Find last ping of the external node, to roughly estimate the time difference and verify if a new seed should have already
    // been generated
    val lastPing = _jvm_histories.get(requestVotes.jvmId) match {
      case Some(history) => history.lastPingWrapper()
      case None => None
    }

    if(_lastRepliedRequestVotes.isDefined) {
      Right(RequestVotesRefused(context.self, requestVotes, _otherRequestVotes, s"Already replied to another RequestVotes (${_lastRepliedRequestVotes.get})."))
    } else if(_lastRequestVotes.isDefined && requestVotes.jvmId != currentJvmId && requestVotes.termNumber < _termNumber) {
      Right(RequestVotesRefused(context.self, requestVotes, _otherRequestVotes, s"Smaller term number than the one we have: ${requestVotes.termNumber} < ${_termNumber}"))
    } else if(_lastRequestVotes.isDefined && requestVotes.jvmId == currentJvmId){
      // We cannot reset our own term variables
      Left(RequestVotesAccepted(context.self, requestVotes, _otherRequestVotes))
    } else {
      _termNumber = requestVotes.termNumber
      resetTermVariables()
      _lastRepliedRequestVotes = Option(requestVotes)
      Left(RequestVotesAccepted(context.self, requestVotes, _otherRequestVotes))
    }
  }

  /**
    * Reset variables related to a previous term
    */
  def resetTermVariables(): Unit = {
    _lastRequestVotes = None
    _lastRepliedRequestVotes = None
    _requestVotesReplies = List.empty[RequestVotesReplyWrapper]
  }

  /**
    * Handle the reply of an ElectionSeed we sent. Return true if we become leader.
    */
  def becomeLeaderWithReplyFromElectionSeed(requestVotesResult: RequestVotesReply): Boolean = {
    if(lastLeader.isDefined) {
      logger.debug(s"A leader has already been defined ($lastLeader), the reply from the external node does not really matter.")
    } else if(requestVotesResult.requestVotes.termNumber != _termNumber) {
      logger.debug(s"Got result for an obsolete RequestVotes message: ${requestVotesResult.requestVotes.termNumber} != ${_termNumber}. We cannot accept this message.")
    } else if(requestVotesResult.isInstanceOf[RequestVotesRefused]) {
      logger.debug(s"Got one rejection for the RequestVotes: ${requestVotesResult.asInstanceOf[RequestVotesRefused].reason}.")
      // Even if we get one rejection, we should accept other messages, as we only need the majority of nodes.
      _requestVotesReplies = _requestVotesReplies :+ RequestVotesReplyWrapper(requestVotesResult)
    } else if(requestVotesResult.isInstanceOf[RequestVotesAccepted]) {
      logger.debug("Got a reply accepting our RequestVotes, store it.")
      _requestVotesReplies = _requestVotesReplies :+ RequestVotesReplyWrapper(requestVotesResult)
    } else{
      logger.error(s"Weird, we received an RequestVotesReply ($requestVotesResult) with an unsupported type.")
    }

    // If enough replies, we might decide to become leader, if there is no timeout
    val acceptingJvmIds = _requestVotesReplies.filter(_.requestVotesReply.isInstanceOf[RequestVotesAccepted]).map(_.requestVotesReply.jvmId)
    if(acceptingJvmIds.length >= nodesForMajority) { // nodesForMajority, minus 1 for the current node
      if(hasRequestVotesTimeout) {
        logger.debug(s"We got ${acceptingJvmIds.length} RequestVotesAccepted messages, but we cannot become primary as there is a timeout.")
        false
      } else {
        logger.debug(s"We got ${acceptingJvmIds.length} RequestVotesAccepted messages ($acceptingJvmIds), we can become primary. Term is ${termNumber}.")
        lastLeader = Option(context.self)
        lastLeaderWrapper = Option(actorRefWrapper)
        true
      }
    } else {
      false
    }
  }

  /**
    * Return true if we didn't get enough RequestVotes in the expected time,.
    * In that case, a new term must be started / or we should switch to candidate state.
    */
  def hasRequestVotesTimeout: Boolean = {
    _lastRequestVotes match {
      case None =>
        logger.debug("No request votes created by ourselves, nothing to do.")
        false
      case Some(requestVotes) =>
        val maxDifference = electionConfigurationService.electionAttemptMaxFrequency.toMillis
        logger.debug(s"Checking request votes timeout: ${maxDifference} vs ${requestVotes.creationDatetime.getMillis + maxDifference} > ${ElectionTools.datetime().getMillis}")
        requestVotes.creationDatetime.getMillis + maxDifference < ElectionTools.datetime().getMillis
    }
  }


  /**
    * Return true if we didn't get enough Ping from a given node (the leader for example) in the expected time,.
    * In that case, we should switch to candidate state.
    */
  def hasPingTimeout(jvmId: String): Boolean = {
    _jvm_histories.get(jvmId) match {
      case None =>
        logger.warn("No ping received from any other nodes, nothing to do.")
        false
      case Some(history) =>
        val maxDifference = electionConfigurationService.heartbeatCheckFrequency.toMillis
        history.lastPingWrapper() match {
          case None =>
            logger.warn("No ping received from any other nodes, nothing to do.")
            false
          case Some(wrapper) =>
            // We allow a bit more than the maxDifference, as there will always be some delay
            wrapper.creationDatetime.getMillis + 5*maxDifference < ElectionTools.datetime().getMillis
        }
    }
  }

  /**
    * If we have a leader, return the appropriate jvm id
    * @return
    */
  def jvmIdForLeader(): Option[String] = {
    lastLeader match {
      case Some(res) => jvmIdForActorRef(res)
      case None => None
    }
  }

  /**
    * Indicate if we have a majority of nodes running.
    */
  def enoughReachableNodes: Boolean = {
    jvmActorRefs.values.size >= nodesForMajority
  }

  /**
    * Can we reach all nodes in the configuration?
    * @return
    */
  def allReachableNodes: Boolean = {
    jvmActorRefs.values.size == electionConfigurationService.electionNodes.length
  }

  /**
    * Number of nodes we should have replies from to have a majority
    */
  def nodesForMajority: Int = math.floor(electionConfigurationService.electionNodes.length / 2f).toInt + 1

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
    jvmActorRefs.get(electionNode.jvmId)
  }

  /**
    * Indicate if the current process is an election node or just a watcher
    * @return
    */
  def currentProcessIsElectionNode(): Boolean = {
    electionConfigurationService.electionNodes.exists(electionNode => {
      logger.debug(s"Compare election node ${electionNode.hostname}:${electionNode.port} with current node ${electionConfigurationService.akkaLocalHostname}:${electionConfigurationService.akkaLocalPort}")
      s"${electionNode.hostname}:${electionNode.port}" == s"${electionConfigurationService.akkaLocalHostname}:${electionConfigurationService.akkaLocalPort}"
    })
  }

  /**
    * We only allow messages from machines with an ip / hostname in the configuration
    */
  def actorRefInConfig(actorRef: ActorRef, message: Any): Boolean = {
    val remotePath = ElectionTools.remoteActorPath(actorRef)

    // The remote actor path is not always valid (it does not contain the hostname + port) if there is a missing configuration
    if (!remotePath.contains("@")) { // Valid: akka.tcp://ElectionSystem@127.0.0.1:2182/user/ElectionActor, invalid: akka://application/user/worker
      throw new Exception(s"Missing configuration to have a valid remote actor path for $actorRef.")
    }

    val isInConfig = electionConfigurationService.electionNodes.exists(electionNode => {
      electionNode.akkaUri == remotePath
    })

    if(!isInConfig) {
      logger.warn(s"We received a message from an actorRef (${actorRef.path.toString} / $remotePath not in the configured nodes: ${electionConfigurationService.electionNodes}. Message: $message")
    }

    isInConfig
  }

  /**
    * Return the current jvmId
    */
  def currentJvmId: String = new RemoteMessage(context.self).jvmId
}
