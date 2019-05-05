package net.degols.libs.election

import java.util.concurrent.{ExecutorService, Executors}
import javax.inject.Inject

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}

/**
  * Easy to use wrapper to have an election system around any actor. An ElectionWrapper can be a simple follower or
  * participating in the election.
  */
abstract class ElectionWrapper @Inject()(electionService: ElectionService, configurationService: ConfigurationService) extends Actor {
  private val logger = LoggerFactory.getLogger(getClass)

  // We use the ActorSystem
  val threadPool: ExecutorService = Executors.newFixedThreadPool(1)
  implicit val executionContext: ExecutionContextExecutorService = ExecutionContext.fromExecutorService(threadPool)
  val config: Config = configurationService.electionConfig

  val systemName: String = if(electionService.currentProcessIsElectionNode()) ConfigurationService.ElectionSystemName
  else ConfigurationService.WatcherSystemName

  val actorSystem: ActorSystem = ActorSystem(systemName, config)

  // Depending on the configuration, we take part in the election, or we simply watch it
  val election: ActorRef = if(electionService.currentProcessIsElectionNode()) actorSystem.actorOf(Props(classOf[ElectionActor], electionService, configurationService), ConfigurationService.ElectionActorName)
  else actorSystem.actorOf(Props(classOf[WatcherActor], electionService, configurationService), ConfigurationService.ElectionActorName)
  election ! IAmTheParent(self)

  private var _isLeader: Boolean = false
  private var _currentLeader: Option[ActorRef] = None
  private var _currentLeaderWrapper: Option[ActorRef] = None

  def currentLeader: Option[ActorRef] = _currentLeader
  def currentLeaderWrapper: Option[ActorRef] = _currentLeaderWrapper
  def isLeader: Boolean = _isLeader

  override def aroundReceive(receive: Receive, msg: Any): Unit = {
    logger.debug(s"[ElectionWrapper] Around Receive: $msg")
    msg match {
      case IAmLeader => // Message received if we take part in the election
        _currentLeader = electionService.lastLeader
        _currentLeaderWrapper = electionService.lastLeaderWrapper
        _isLeader = true
      case IAmFollower => // Message received if we take part in the election
        _currentLeader = electionService.lastLeader
        _currentLeaderWrapper = electionService.lastLeader
        _isLeader = false
      case leader: TheLeaderIs => // Message received if we got an update about the current leader (it might be None)
        _currentLeader = leader.leader
        _currentLeaderWrapper = leader.leaderWrapper
      case x => // Message is handled below
    }

    // Also useful to call the default method for internal messages, that way the developer can subscribe to it
    super.aroundReceive(receive, msg)
  }
}
