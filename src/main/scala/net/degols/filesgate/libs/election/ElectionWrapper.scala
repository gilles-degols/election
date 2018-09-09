package net.degols.filesgate.libs.election

import java.util.concurrent.{ExecutorService, Executors}
import javax.inject.Inject

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService}

/**
  * Easy to use wrapper to have an election system around any actor
  */
abstract class ElectionWrapper @Inject()(electionService: ElectionService, configurationService: ConfigurationService) extends Actor {
  // We use the ActorSystem
  val threadPool: ExecutorService = Executors.newFixedThreadPool(1)
  implicit val executionContext: ExecutionContextExecutorService = ExecutionContext.fromExecutorService(threadPool)

  val config = configurationService.fallbackConfig
  println("Try to start the actor system...")
  val actorSystem = ActorSystem(ConfigurationService.ElectionSystemName, config)
  val election: ActorRef = actorSystem.actorOf(Props(classOf[ElectionActor], electionService, configurationService), ConfigurationService.ElectionActorName)

  private var isLeader: Boolean = false

  override def aroundReceive(receive: Receive, msg: Any): Unit = {
    receive(msg)
  }
}
