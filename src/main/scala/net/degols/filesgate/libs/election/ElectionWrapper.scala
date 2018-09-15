package net.degols.filesgate.libs.election

import java.util.concurrent.{ExecutorService, Executors}
import javax.inject.Inject

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}

/**
  * Easy to use wrapper to have an election system around any actor
  */
abstract class ElectionWrapper @Inject()(electionService: ElectionService, configurationService: ConfigurationService) extends Actor {
  private val logger = LoggerFactory.getLogger(getClass)

  // We use the ActorSystem
  val threadPool: ExecutorService = Executors.newFixedThreadPool(1)
  implicit val executionContext: ExecutionContextExecutorService = ExecutionContext.fromExecutorService(threadPool)
  println(s"Current actor: ${self}")
  val config: Config = configurationService.fallbackConfig.getConfig("election")
  val actorSystem = ActorSystem(ConfigurationService.ElectionSystemName, config)
  val election: ActorRef = actorSystem.actorOf(Props(classOf[ElectionActor], electionService, configurationService), ConfigurationService.ElectionActorName)
  election ! IAmTheParent(self)

  private var _isLeader: Boolean = false

  def isLeader: Boolean = _isLeader

  override def aroundReceive(receive: Receive, msg: Any): Unit = {
    logger.debug(s"[ElectionWrapper] Around Receive: $msg")
    msg match {
      case IAmLeader => _isLeader = true
      case IAmFollower => _isLeader = false
      case x => receive(msg)
    }

    // We forward the IAmLeader / IAmFollower message if the system wants to handle it correctly
    receive(msg)
  }
}
