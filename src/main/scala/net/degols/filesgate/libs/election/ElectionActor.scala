package net.degols.filesgate.libs.election

import javax.inject.Inject

import akka.actor.{Actor, ActorRef, Kill, Terminated}
import org.slf4j.LoggerFactory

import scala.util.Random
import scala.concurrent.duration._

/**
  * The external code using the election system will need to instantiate one instance of the ElectionActor with the fallback
  */
@Singleton
class ElectionActor @Inject()(electionService: ElectionService, configurationService: ConfigurationService) extends Actor {
  private val logger = LoggerFactory.getLogger(getClass)
  private val random = new Random(System.currentTimeMillis())

  // TODO: Add actor to notify once a leader has been found. We also need to implement simple watchers of the election, not trying to
  // become master, but still being warned if a leader changed.

  override def preStart(): Unit = {
    electionService.context = context
    self ! BecomeFollower
  }

  /**
    * The receive state has almost nothing to do, it should directly switch to the waiting state.
    * @return
    */
  override def receive: Receive = {
    case BecomeFollower =>
      logger.info(s"[Receive state] Switch from the receive state to the waiting state.")
      context.become(follower)

      // Try to discover the other nodes (=actorRef) some times.
      context.system.scheduler.schedule(configurationService.discoverNodesFrequency, configurationService.discoverNodesFrequency,
                                        self, DiscoverNodes)

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
      handlePingMessage(message, sender(), "candidate")

    case SendPingMessages | DiscoverNodes =>
      // In the candidate state we do not need to send Ping messages, nor discover the other nodes

    case CheckPingMessages =>
      electionService.checkPingFromAllNodes()

    case message: RequestVotes =>
      logger.debug("[Candidate state] Receive a RequestVotes message from another candidate, reply to it.")
      val reply = electionService.replyToRequestVotes(message)
      if(reply.isLeft) {
        logger.debug("[Candidate state] Accepting the external RequestVotes.")
        sender() ! reply.left.get
      } else {
        logger.debug(s"[Candidate state] Rejecting the external RequestVotes, reason: ${reply.right.get.reason}.")
        sender() ! reply.right.get

        if(message.termNumber > electionService.termNumber) {
          logger.debug(s"[Candidate state] Got a bigger term number from another candidate, switch to follower state.")
          electionService.increaseTermNumber(Option(message.termNumber))
          context.become(follower)
        }
      }

    case AttemptElection =>
      electionService.lastLeader match {
        case Some(res) =>
          logger.debug("[Candidate state] A leader is still known, we do not trigger a new election.")
        case None =>
          logger.info("[Candidate state] Triggering a new election.")
          electionService.sendRequestVotes()
      }

    case message: RequestVotesAccepted | RequestVotesRefused =>
      logger.debug("[Candidate state] Received a reply to our RequestVotes message.")
      val becameLeader = electionService.becomeLeaderWithReplyFromElectionSeed(message)
      if(becameLeader) {
        context.become(leader)
        // We need to contact the other nodes directly, to inform them of their new leader.
        electionService.sendPingToKnownNodes()
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

    case SendPingMessages | DiscoverNodes =>
      // In the follower state we do not need to send Ping messages, nor discover the other nodes
      // TODO: Maybe it would still be interesting to have the actorRef from every node to allow a faster (smoother?) election
      // when we are in the candidate state?

    case CheckPingMessages =>
      electionService.checkPingFromAllNodes()


    case BecomeCandidate =>
      logger.info("[Follower state] Change to Candidate state")
      electionService.increaseTermNumber(None)
      context.become(candidate)
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
      val reply = electionService.replyToRequestVotes(message)
      if(reply.isLeft) {
        logger.debug("[Follower state] Accepting the external RequestVotes.")
        sender() ! reply.left.get
      } else {
        logger.debug(s"[Follower state] Rejecting the external RequestVotes, reason: ${reply.right.get.reason}.")
        sender() ! reply.right.get

        if(message.termNumber > electionService.termNumber) {
          logger.debug(s"[Follower state] Got a bigger term number from another candidate, update the term number.")
          electionService.increaseTermNumber(Option(message.termNumber))
        }
      }

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
      electionService.sendPingToUnreachableNodes()

    case CheckPingMessages =>
      electionService.checkPingFromAllNodes()

    case Terminated(externalActor) =>
      val jvmId = electionService.jvmIdForActorRef(externalActor)
      logger.info(s"[Leader state] Got a Terminated message from external actor: $externalActor. Jvm Id: $jvmId")
      electionService.unwatchJvm(externalActor)

      if(!electionService.enoughReachableNodes) {
        logger.warn("[Leader state] Not enough reachable nodes, we cannot be leader anymore. Kill ourselves.")
        // We trigger a kill of the current actor, to notify easily the other nodes.
        self ! Kill
      }

    case SendPingMessages =>
      electionService.sendPingToAllNodes()

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
          context.become(follower)
        }
      }
      electionService.increaseTermNumber(Option(message.termNumber))
      electionService.lastLeader = electionService.leaderFromPings
    }
  }

  private def scheduleNextTerm(): Unit = {
    // Random delay to start the RequestVotes, according to the Raft algorithm to avoid having too many split voting
    val delay = configurationService.electionAttemptMaxFrequency.toMillis - configurationService.electionAttemptMinFrequency.toMillis
    val requestVotesDelay = configurationService.electionAttemptMinFrequency.toMillis + random.nextInt(delay.toInt)
    context.system.scheduler.scheduleOnce(requestVotesDelay millis, self, AttemptElection)
  }
}
