package net.degols.filesgate.libs.election

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

  val systemName: String = if(electionService.actorRefInConfig(self)) ConfigurationService.ElectionSystemName
  else ConfigurationService.WatcherActorName
  val actorSystem: ActorSystem = ActorSystem(systemName, config)

  // Depending on the configuration, we take part in the election, or we simply watch it
  val election: ActorRef = if(electionService.actorRefInConfig(self)) actorSystem.actorOf(Props(classOf[ElectionActor], electionService, configurationService), ConfigurationService.ElectionActorName)
  else actorSystem.actorOf(Props(classOf[WatcherActor], electionService, configurationService), ConfigurationService.ElectionActorName)

  election ! IAmTheParent(self)

  private var _isLeader: Boolean = false
  private var _currentLeader: Option[ActorRef] = None

  def currentLeader: Option[ActorRef] = _currentLeader
  def isLeader: Boolean = _isLeader

  override def aroundReceive(receive: Receive, msg: Any): Unit = {
    super.aroundReceive(receive, msg)

    logger.debug(s"[ElectionWrapper] Around Receive: $msg")
    msg match {
      case IAmLeader => // Message received if we take part in the election
        _currentLeader = electionService.lastLeader
        _isLeader = true
      case IAmFollower => // Message received if we take part in the election
        _currentLeader = electionService.lastLeader
        _isLeader = false
      case TheLeaderIs(leader) => // Message received if we got an update about the current leader (it might be None)
        _currentLeader = leader
      case x => // Message is forwarded below
    }

    receive(msg)
  }
}
