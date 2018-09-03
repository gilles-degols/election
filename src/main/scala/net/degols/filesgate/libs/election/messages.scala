package net.degols.filesgate.libs.election

import akka.actor.ActorRef
import org.joda.time.DateTime

class RemoteMessage(actorRef: ActorRef) {
  // Id that we remain the same even if the jvm restart
  def jvmId: String = actorRef.toString().split("/user/").head
  val creationDatetime: DateTime = new DateTime()

  override def toString: String = s"RemoteMessage: $actorRef"
}

/**
  * Message used to notify every ElectionActor of the existence of the JVM
 *
  * @param actorRef remote actor ref
  */
case class Ping(actorRef: ActorRef) extends RemoteMessage(actorRef){
  override def toString: String = s"Ping: $actorRef @ $creationDatetime"
}

/**
  * Internal messages
  */
case object BecomeWaiting
case object BecomeRunning

/**
  * Order to send Ping messages to every nodes. Also used to discover nodes.
  */
case object SendPingMessages

/**
  * The leader was lost, or there is currently no leader (when the process is starting up)
  */
case object AttemptElection
