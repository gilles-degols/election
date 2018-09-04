package net.degols.filesgate.libs.election

import javax.inject.Inject

import akka.actor.{Actor, ActorRef, Kill, Terminated}
import org.slf4j.LoggerFactory

/**
  * The external code using the election system will need to instantiate one instance of the ElectionActor with the fallback
  */
@Singleton
class ElectionActor @Inject()(electionService: ElectionService, configurationService: ConfigurationService) extends Actor {
  private val logger = LoggerFactory.getLogger(getClass)


  override def preStart(): Unit = {
    electionService.context = context
    self ! BecomeWaiting
  }

  /**
    * The receive state has almost nothing to do, it should directly switch to the waiting state.
    * @return
    */
  override def receive: Receive = {
    case BecomeWaiting =>
      logger.info(s"[Receive state] Switch from the receive state to the waiting state.")
      context.become(waiting)
      context.system.scheduler.schedule(configurationService.discoverNodesFrequency, configurationService.discoverNodesFrequency,
                                        self, SendPingMessages)

      // This is a security in case some Akka messages are lost, we want to be sure to retry an election for any problem
      context.system.scheduler.schedule(configurationService.electionAttemptFrequency, configurationService.electionAttemptFrequency,
        self, AttemptElection)

    case x =>
      if(electionService.actorRefInConfig(sender())) {
        logger.warn(s"[Receive state] Unknown message received in the ElectionActor: $x")
      }
  }

  /**
    * The process is waiting for the election to start, or to find the current leader
    * @return
    */
  def waiting: Receive = {// Waiting / slave status
    case message: Ping =>
      if(electionService.actorRefInConfig(sender())) {
        logger.info(s"[Waiting state] Received Ping message: $message")
        electionService.addJvmMessage(message)
        electionService.watchJvm(message.jvmId, message.actorRef)
        electionService.lastLeader = electionService.leaderFromPings
      }

    case Terminated(externalActor) =>
      // We simply unwatch the actor. We do not need to schedule a DiscoverNodes as the external node should do that
      // once it has been re-started.

      val jvmId = electionService.jvmIdForActorRef(externalActor)
      logger.info(s"[Receive state] Got a Terminated message from external actor: $externalActor. Jvm Id: $jvmId")
      electionService.unwatchJvm(externalActor)

      if(electionService.lastLeader.isDefined && electionService.lastLeader.get == externalActor) {
        logger.info("[Receive state] The Terminated message belonged to the leader, trigger a new election.")
        electionService.lastLeader = None
        self ! AttemptElection
      }

    case SendPingMessages =>
      electionService.sendPingToAllNodes()

    case AttemptElection =>
      electionService.lastLeader match {
        case Some(res) => logger.debug("[Waiting state] A leader is still known, we do not trigger a new election.")
        case None =>
          logger.info("[Waiting state] Triggering a new election.")
          electionService.sendElectionSeeds()
      }

    case message: ElectionSeed =>
      logger.debug("[Waiting state] Received an external ElectionSeed message, reply to it.")
      val reply = electionService.replyToElectionSeed(message)
      if(reply.isLeft) {
        logger.debug("[Waiting state] Accepting the external ElectionSeed.")
        sender() ! reply.left.get
      } else {
        logger.debug(s"[Waiting state] Rejecting the external ElectionSeed, reason: ${reply.right.get.reason}.")
        sender() ! reply.right.get
      }

    case message: ElectionSeedAccepted | ElectionSeedRefused =>
      logger.debug("[Waiting state] Received a reply to our ElectionSeed message.")
      val becameLeader = electionService.becomeLeaderWithReplyFromElectionSeed(message)
      if(becameLeader) {
        context.become(leader)
      }

    case x =>
      if(electionService.actorRefInConfig(sender())) {
        logger.warn(s"[Waiting state] Unknown message received in the ElectionActor: $x")
      }
  }

  def leader: Receive = {// Leader status
    case message: Ping =>
      if(electionService.actorRefInConfig(sender())) {
        logger.info(s"[Leader state] Received Ping message: $message")
        electionService.addJvmMessage(message)
        electionService.watchJvm(message.jvmId, message.actorRef)
      }

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

}
