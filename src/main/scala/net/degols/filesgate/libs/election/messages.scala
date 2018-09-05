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
case class Ping(actorRef: ActorRef, leaderActorRef: Option[ActorRef], termNumber: Long) extends RemoteMessage(actorRef){
  override def toString: String = s"Ping: $actorRef @ $creationDatetime"
}

/**
  * When a new election is needed, a message is sent to every process. We cannot simply assume that if A can talk to B,
  * the opposite is true. Because of that, the election needs an acknowledgment from each node of the majority.
  * The "termNumber" is a monotonously increasing number, to use the Raft algorithm, to decide of a winning node. The node with the highest value will be elected
  * as leader (if other conditions are met).
  */
case class RequestVotes(actorRef: ActorRef, termNumber: Long) extends RemoteMessage(actorRef) {
  override def toString: String = s"RequestVotes: $actorRef @ $creationDatetime"
}

abstract class RequestVotesReply(actorRef: ActorRef, val requestVotes: RequestVotes, val otherElectionSeeds: List[RequestVotes]) extends RemoteMessage(actorRef)

case class RequestVotesAccepted(actorRef: ActorRef, override val requestVotes: RequestVotes, override val otherElectionSeeds: List[RequestVotes]) extends RequestVotesReply(actorRef, electionSeed, otherElectionSeeds) {
  override def toString: String = s"RequestVotesAccepted: $actorRef @ $creationDatetime"
}
case class RequestVotesRefused(actorRef: ActorRef, override val requestVotes: RequestVotes, override val otherElectionSeeds: List[RequestVotes], reason: String) extends RequestVotesReply(actorRef, electionSeed, otherElectionSeeds) {
  override def toString: String = s"RequestVotesRefused: $actorRef @ $creationDatetime, reason: $reason"
}

/**
  * Internal messages
  */
case object BecomeCandidate
case object BecomeFollower
case object BecomeLeader

/**
  * Order to send Ping messages to every nodes. Also used to discover nodes.
  */
case object SendPingMessages

/**
  * The leader was lost, or there is currently no leader (when the process is starting up)
  */
case object AttemptElection

