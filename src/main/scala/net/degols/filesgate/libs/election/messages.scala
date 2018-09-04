package net.degols.filesgate.libs.election

import akka.actor.ActorRef
import org.joda.time.{DateTime, DateTimeZone}

class RemoteMessage(actorRef: ActorRef) {
  // Id that we remain the same even if the jvm restart
  def jvmId: String = actorRef.toString().split("/user/").head
  val creationDatetime: DateTime = new DateTime().withZone(DateTimeZone.UTC)

  override def toString: String = s"RemoteMessage: $actorRef"
}

/**
  * Message used to notify every ElectionActor of the existence of the JVM. It also sends the last leader actor (if found)
 *
  * @param actorRef remote actor ref
  */
case class Ping(actorRef: ActorRef, leaderActorRef: Option[ActorRef]) extends RemoteMessage(actorRef){
  override def toString: String = s"Ping: $actorRef @ $creationDatetime"
}

/**
  * When a new election is needed, a message is sent to every process. We cannot simply assume that if A can talk to B,
  * the opposite is true. Because of that, the election needs an acknowledgment from each node of the majority.
  * The "id" is auto-incremented to keep track of sent messages of ElectionSeed, to be sure that we receive an acknowledgment
  * from the last ElectionSeed (time will be checked as well). The same id is used to send the same messages to multiple actors.
  * The "seed" is a randomly generated number, to decide of a winning node. The node with the highest value will be elected
  * as master. A check will be done to avoid accepting the same seed for two nodes.
  */
case class ElectionSeed(actorRef: ActorRef, seed: Long, id: Long) extends RemoteMessage(actorRef) {
  override def toString: String = s"ElectionSeed: $actorRef @ $creationDatetime"
}

abstract class ElectionSeedReply(actorRef: ActorRef, val electionSeed: ElectionSeed) extends RemoteMessage(actorRef)

case class ElectionSeedAccepted(actorRef: ActorRef, override val electionSeed: ElectionSeed) extends ElectionSeedReply(actorRef, electionSeed) {
  override def toString: String = s"ElectionSeedAccepted: $actorRef @ $creationDatetime"
}
case class ElectionSeedRefused(actorRef: ActorRef, override val electionSeed: ElectionSeed, reason: String) extends ElectionSeedReply(actorRef, electionSeed) {
  override def toString: String = s"ElectionSeedRefused: $actorRef @ $creationDatetime, reason: $reason"
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

