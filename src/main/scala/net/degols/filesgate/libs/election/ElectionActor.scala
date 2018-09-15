package net.degols.filesgate.libs.election

import javax.inject.{Inject, Singleton}

import akka.actor.{Actor, ActorRef, Kill, Terminated}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random
import scala.concurrent.duration._

/**
  * The external code using the election system will need to instantiate one instance of the ElectionActor with the fallback
  */
@Singleton
class ElectionActor @Inject()(electionService: ElectionService, configurationService: ConfigurationService) extends Actor {
  private val logger = LoggerFactory.getLogger(getClass)
  private val random = new Random(System.currentTimeMillis())
  private var parent: Option[IAmTheParent] = _
  // TODO: Add actor to notify once a leader has been found. We also need to implement simple watchers of the election, not trying to
  // become master, but still being warned if a leader changed.

  override def preStart(): Unit = {
    electionService.context = context
  }

  /**
    * The receive state has almost nothing to do, it should directly switch to the waiting state as soon as it received
    * the parent information.
    * @return
    */
  override def receive: Receive = {
    case iamTheParent: IAmTheParent =>
      logger.info(s"[Receive state] Switch from the receive state to the follower state.")
      parent = Option(iamTheParent)
      context.become(follower)

      // Try to discover the other nodes (=actorRef) some times.
      context.system.scheduler.schedule(configurationService.discoverNodesFrequency, configurationService.discoverNodesFrequency,
                                        self, DiscoverNodes)

      // RequestVotes timeout
      context.system.scheduler.schedule(configurationService.electionAttemptMaxFrequency, configurationService.electionAttemptMaxFrequency,
        self, RequestVotesTimeoutCheck)

      // Send hearbeat frequently
      context.system.scheduler.schedule(configurationService.heartbeatFrequency, configurationService.heartbeatFrequency,
        self, SendPingMessages)

      // Check for heartbeat (more) frequently, based on the local time of the server, not the external time
      context.system.scheduler.schedule(configurationService.heartbeatCheckFrequency, configurationService.heartbeatCheckFrequency,
        self, CheckPingMessages)

    case x =>
      if(electionService.actorRefInConfig(sender())) {
        logger.warn(s"[Receive state] Unknown message received in the ElectionActor: $x")
      }
  }

  def candidate: Receive = { // Trying to be leader
    case message: Ping =>
      // If a new term is found, we will switch to follower state
      handlePingMessage(message, sender(), "candidate")

    case SendPingMessages | DiscoverNodes =>
      // In the candidate state we do not need to send Ping messages, nor discover the other nodes

    case CheckPingMessages =>
      // In this case, we don't really need to check the ping timeout as we are not following anyone

    case RequestVotesTimeoutCheck =>
      logger.debug("[Candidate state] Check RequestVotes timeout.")
      if(electionService.hasRequestVotesTimeout) {
        logger.warn(s"[Candidate state] RequestVotes timeout for the term ${electionService.termNumber}, schedule a new term.")
        scheduleNextTerm()
      }

    case message: RequestVotes =>
      val send = sender()
      logger.debug(s"[Candidate state] Receive a RequestVotes message from another candidate, reply to it ($send).")
      handleRequestVotes(message, sender(), "candidate")
      // TODO: HANDLE TIMEOUT !

    case AttemptElection =>
      electionService.lastLeader match {
        case Some(res) =>
          logger.debug("[Candidate state] A leader is still known, we do not trigger a new election.")
        case None =>
          logger.info("[Candidate state] Triggering a new election.")
          electionService.sendRequestVotes()
      }

    case message: RequestVotesReply =>
      logger.debug("[Candidate state] Received a reply to our RequestVotes message.")
      val becameLeader = electionService.becomeLeaderWithReplyFromElectionSeed(message)
      if(becameLeader) {
        context.become(leader)
        // We need to contact the other nodes directly, to inform them of their new leader.
        electionService.sendPingToKnownNodes()
        // We tell the parent actor that we become the leader
        logger.debug(s"[Candidate state] Warning the parent actor of the new state: ${context.parent}")
        parent.get.actorRef ! IAmLeader
      }

    case x =>
      if(electionService.actorRefInConfig(sender())) {
        logger.warn(s"[Candidate state] Unknown message received in the ElectionActor: $x")
      }
  }

  /**
    * The process is waiting for the election to start, or to find the current leader
    * @return
    */
  def follower: Receive = {// Waiting / slave status
    case message: Ping =>
      handlePingMessage(message, sender(), "follower")

    case SendPingMessages | DiscoverNodes | RequestVotesTimeoutCheck =>
      // In the follower state we do need to send Ping messages, nor discover the other nodes
      // TODO: Maybe it would still be interesting to have the actorRef from every node to allow a faster (smoother?) election
      // when we are in the candidate state?

    case CheckPingMessages =>
      electionService.jvmIdForLeader() match {
        case None =>
          logger.debug("[Follower state] No leader during the check of Ping messages, switch to candidate state.")
          self ! BecomeCandidate
        case Some(res) =>
          if(electionService.hasPingTimeout(res)) {
            logger.debug("[Follower state] Ping timeout from the other nodes, switch to candidate state.")
            self ! BecomeCandidate
          }
      }

    case BecomeCandidate =>
      logger.info("[Follower state] Change to Candidate state")
      electionService.increaseTermNumber(None)
      context.become(candidate)
      parent.get.actorRef ! IAmFollower // No difference between candidate & follower for the parent
      scheduleNextTerm()

    case Terminated(externalActor) =>
      // We simply unwatch the actor. We do not need to schedule a DiscoverNodes as the external node should do that
      // once it has been re-started.

      val jvmId = electionService.jvmIdForActorRef(externalActor)
      logger.info(s"[Follower state] Got a Terminated message from external actor: $externalActor. Jvm Id: $jvmId")
      electionService.unwatchJvm(externalActor)

      if(electionService.lastLeader.isDefined && electionService.lastLeader.get == externalActor) {
        logger.info("[Follower state] The Terminated message belonged to the leader, trigger a new election.")
        electionService.lastLeader = None
        self ! BecomeCandidate
      }

    case message: RequestVotes =>
      logger.debug("[Follower state] Received a RequestVotes, reply to it.")
      handleRequestVotes(message, sender(), "follower")

    case x =>
      if(electionService.actorRefInConfig(sender())) {
        logger.warn(s"[Follower state] Unknown message received in the ElectionActor: $x")
      }
  }

  def leader: Receive = {// Leader status
    case message: Ping =>
      handlePingMessage(message, sender(), "leader")

    case SendPingMessages =>
      electionService.sendPingToKnownNodes()

    case DiscoverNodes =>
      logger.debug("[Leader state] Discovering nodes.")
      electionService.sendPingToUnreachableNodes()

    case CheckPingMessages =>
      // We do not need to check Ping messages from the followers / candidates.

    case message: RequestVotes =>
      logger.debug("[Leader state] Receive a RequestVotes message from another candidate, reply to it.")
      handleRequestVotes(message, sender(), "candidate")

    case Terminated(externalActor) =>
      val jvmId = electionService.jvmIdForActorRef(externalActor)
      logger.info(s"[Leader state] Got a Terminated message from external actor: $externalActor. Jvm Id: $jvmId")
      electionService.unwatchJvm(externalActor)

      if(!electionService.enoughReachableNodes) {
        logger.warn("[Leader state] Not enough reachable nodes, we cannot be leader anymore. Kill ourselves.")
        // We trigger a kill of the current actor, to notify easily the other nodes.
        self ! Kill
      }

    case RequestVotesTimeoutCheck =>
      // Nothing to do

    case x =>
      if(electionService.actorRefInConfig(sender())) {
        logger.warn(s"[Leader state] Unknown message received in the ElectionActor: $x")
      }
  }


  private def handlePingMessage(message: Ping, sender: ActorRef, contextType: String): Unit = {
    if(electionService.actorRefInConfig(sender)) {
      logger.info(s"[$contextType state] Received Ping message: $message")
      electionService.addJvmMessage(message)
      electionService.watchJvm(message.jvmId, message.actorRef)
      if(message.termNumber > electionService.termNumber) {
        if(contextType == "leader" || contextType == "candidate") {
          logger.debug(s"[$contextType state] $contextType has received a Ping with a bigger term, switch to follower.")
          parent.get.actorRef ! IAmFollower
          context.become(follower)
        }
      }
      electionService.increaseTermNumber(Option(message.termNumber))
      electionService.lastLeader = electionService.leaderFromPings
    } else {
      logger.warn(s"[$contextType state] Received Ping message: $message, but the sender is not in the config, we do not accept it")
    }
  }

  private def handleRequestVotes(message: RequestVotes, sender: ActorRef, contextType: String): Unit = {
    val reply = electionService.replyToRequestVotes(message)
    if(reply.isLeft) {
      logger.debug(s"[$contextType state] Accepting the external RequestVotes.")
      sender ! reply.left.get
    } else {
      logger.debug(s"[$contextType state] Rejecting the external RequestVotes, reason: ${reply.right.get.reason}.")
      sender ! reply.right.get

      if(message.termNumber > electionService.termNumber) {
        logger.debug(s"[$contextType state] Got a bigger term number from another candidate, update the term number.")
        electionService.increaseTermNumber(Option(message.termNumber))
      }
    }
  }

  private def scheduleNextTerm(): Unit = {
    // Random delay to start the RequestVotes, according to the Raft algorithm to avoid having too many split voting
    val delay = configurationService.electionAttemptMaxFrequency.toMillis - configurationService.electionAttemptMinFrequency.toMillis
    val requestVotesDelay = configurationService.electionAttemptMinFrequency.toMillis + random.nextInt(delay.toInt)
    context.system.scheduler.scheduleOnce(requestVotesDelay millis, self, AttemptElection)
  }
}
