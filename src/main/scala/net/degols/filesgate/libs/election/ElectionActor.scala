package net.degols.filesgate.libs.election

import akka.actor.Actor
import org.slf4j.LoggerFactory

/**
  * The external code using the election system will need to instantiate one instance of the ElectionActor with the fallback
  */
class ElectionActor() extends Actor {
  private val logger = LoggerFactory.getLogger(getClass)
  val electionService = new ElectionService()

  override def receive: Receive = {
    case message: Ping =>
      logger.info(s"Received Ping message: $message")
      electionService.addJvmMessage(message)

    case x =>
      logger.warn(s"Unknown message received in the ElectionActor: $x")
  }
}
