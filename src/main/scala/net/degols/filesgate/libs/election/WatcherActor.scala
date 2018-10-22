package net.degols.filesgate.libs.election

import javax.inject.{Inject, Singleton}

import akka.actor.{Actor, ActorRef, Kill, Terminated}
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random
import scala.concurrent.duration._

/**
  * Watch ElectionActors to detect the leaders
  */
@Singleton
class WatcherActor @Inject()(electionService: ElectionService, configurationService: ConfigurationService) extends Actor {
  private val logger = LoggerFactory.getLogger(getClass)
  private val random = new Random(System.currentTimeMillis())
  private var parent: Option[IAmTheParent] = _

  override def preStart(): Unit = {
    super.preStart()
    electionService.context = context

    // Verify the leader frequently (we use the heartbeat frequency)
    context.system.scheduler.schedule(configurationService.heartbeatFrequency, configurationService.heartbeatFrequency,
      self, CheckWhoIsTheLeader)
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
      context.become(watcher)

    case x =>
      logger.warn(s"[Receive state] Unknown message received in the ElectionActor: $x")
  }


  def watcher: Receive = { // watching a specific leader
    case Terminated(externalActor) =>
      logger.info(s"[Watcher state] Got a Terminated message from an actor we were watching: $externalActor.")
      electionService.unwatchJvm(externalActor)
    case CheckWhoIsTheLeader =>
      electionService.sendWhoIsTheLeader()
    case leader: TheLeaderIs =>
      logger.info("[Watcher state] Got TheLeaderIs message, send it to the parent")
      parent.get.actorRef ! leader
    case x =>
      logger.warn(s"[Watcher state] Unknown message received in the ElectionActor: $x")
  }
}
