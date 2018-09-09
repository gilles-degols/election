package net.degols.filesgate.libs.election

import javax.inject.Inject

import akka.actor.{ActorContext, ActorRef}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, TimeoutException}
import scala.util.{Failure, Success, Try, Random}

/**
  * Election system is using the Raft algorithm: https://www.usenix.org/system/files/conference/atc14/atc14-paper-ongaro.pdf
  */
@Singleton
class ElectionService @Inject()(configurationService: ConfigurationService) {
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
  private var _otherRequestVotes: List[RequestVotes] = List.empty[RequestVotes]

  /**
    * List of accepted answers for the _lastRequestVotes.
    */
  private var _requestVotesReplies: List[RequestVotesReplyWrapper] = List.empty[RequestVotesReplyWrapper]

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

  def increaseTermNumber(termNumber: Option[Long]): Unit = if(termNumber.isEmpty) _termNumber += 1 else _termNumber = termNumber.get

  def termNumber: Long = _termNumber

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
    * Retrieve the leader from the Ping messages
    */
  def leaderFromPings: Option[ActorRef] = {
    if(enoughReachableNodes) {
      val distinctLeaders = jvmActorRefs.flatMap(jvm => {
        _jvm_histories(jvm._1).lastPingWrapper()
      }).map(_.remoteMessage.asInstanceOf[Ping]).map(_.actorRef).toList.distinct

      if(distinctLeaders.isEmpty) {
        logger.debug("No leader received from external Ping messages, wait for the election.")
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
    * Send a ping to every actor-ref, they must be reachable first, we don't want to resolve an actor ref here.
    */
  def sendPingToKnownNodes(): Unit = {
    configurationService.electionNodes.foreach(electionNode => {
      actorRefForElectionNode(electionNode) match {
        case Some(res) => // Quite simple to contact it
          logger.debug(s"Send ping to reachable ElectionNode: $electionNode")
          res ! Ping(context.self, lastLeader, _termNumber)
        case None => // Need to resolve the actor path. It might not exist, we are not sure
          logger.debug(s"Do not send ping to previously unreachable ElectionNode: $electionNode")
      }
    })
  }

  def sendPingToUnreachableNodes(): Unit = {
    configurationService.electionNodes.foreach(electionNode => {
      actorRefForElectionNode(electionNode) match {
        case Some(res) => // Nothing to do here
        case None => // Need to resolve the actor path. It might not exist, we are not sure
          logger.debug(s"Send ping to previously unreachable ElectionNode: $electionNode")
          sendPingToUnreachableNode(electionNode, _termNumber)
      }
    })
  }

  /**
    * Try to send a ping to an unreachable node. To avoid locking the system, this is asynchronous.
    * @param electionNode
    */
  private def sendPingToUnreachableNode(electionNode: ElectionNode, termNumber: Long): Future[Try[Unit]] = Future {
    Try {
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
        remoteActorRef ! Ping(context.self, lastLeader, termNumber)
      }
    }
  }

  /**
    * Attempt an election: it sends an ElectionSeed message to every reachable node. If we receive replies fast enough acknowledging
    * it, the current node will be elected.
    */
  def sendRequestVotes(): Unit = {
    if(!enoughReachableNodes) {
      throw new Exception("ElectionSeeds must only be sent if we have enough reachable nodes!")
    }

    // Generate an election seed if we don't have one yet.
    generateElectionSeed()

    configurationService.electionNodes.foreach(electionNode => {
      actorRefForElectionNode(electionNode) match {
        case Some(res) => // Quite simple to contact it
          logger.debug(s"Send ElectionSeed (${_lastRequestVotes.get}) to reachable ElectionNode: $electionNode")
          res ! _lastRequestVotes.get
        case None => // Unreachabled node, we do nothing.
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
    } else {
      _termNumber = requestVotes.termNumber
      resetTermVariables()
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
      logger.debug("Got one rejection for the RequestVotes.")
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
    if(acceptingJvmIds.length >= nodesForMajority - 1) { // nodesForMajority, minus 1 for the current node
      if(hasRequestVotesTimeout) {
        logger.debug(s"We got ${acceptingJvmIds.length} RequestVotesAccepted messages, but we cannot become primary as there is a timeout.")
        false
      } else {
        logger.debug(s"We got ${acceptingJvmIds.length} RequestVotesAccepted messages, we can become primary.")
        lastLeader = Option(context.self)
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
        val maxDifference = configurationService.electionAttemptMaxFrequency.toMillis
        requestVotes.creationDatetime.getMillis + maxDifference > Tools.datetime().getMillis
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
        val maxDifference = configurationService.heartbeatCheckFrequency.toMillis
        history.lastPingWrapper() match {
          case None =>
            logger.warn("No ping received from any other nodes, nothing to do.")
            false
          case Some(wrapper) =>
            wrapper.creationDatetime.getMillis + maxDifference > Tools.datetime().getMillis
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
    * Number of nodes we should have replies from to have a majority
    */
  def nodesForMajority: Int = math.ceil(configurationService.electionNodes.length / 2f).toInt

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

  /**
    * Return the current jvmId
    */
  def currentJvmId: String = new RemoteMessage(context.self).jvmId
}
