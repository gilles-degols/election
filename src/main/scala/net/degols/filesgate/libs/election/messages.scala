package net.degols.filesgate.libs.election

import akka.actor.ActorRef
import org.joda.time.{DateTime, DateTimeZone}

class RemoteMessage(actorRef: ActorRef) {
  // Id that we remain the same even if the jvm restart
  def jvmId: String = akka.serialization.Serialization.serializedActorPath(actorRef).split("/user/").head.replace(".tcp","").replace(".udp","")
  val creationDatetime: DateTime = new DateTime().withZone(DateTimeZone.UTC)

  override def toString: String = s"RemoteMessage: $actorRef"
}

/**
  * Wrapper around any RemoteMessage
  */
class RemoteMessageWrapper(val remoteMessage: RemoteMessage){
  val creationDatetime: DateTime = new DateTime().withZone(DateTimeZone.UTC)

  override def toString: String = s"RemoteMessageWrapper: ${remoteMessage}"
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

case class RequestVotesAccepted(actorRef: ActorRef, override val requestVotes: RequestVotes, override val otherElectionSeeds: List[RequestVotes]) extends RequestVotesReply(actorRef, requestVotes, otherElectionSeeds) {
  override def toString: String = s"RequestVotesAccepted: $actorRef @ $creationDatetime"
}
case class RequestVotesRefused(actorRef: ActorRef, override val requestVotes: RequestVotes, override val otherElectionSeeds: List[RequestVotes], reason: String) extends RequestVotesReply(actorRef, requestVotes, otherElectionSeeds) {
  override def toString: String = s"RequestVotesRefused: $actorRef @ $creationDatetime, reason: $reason"
}

/**
  * Small wrapper to focus on the local reception time (=local creation datetime) and not the external time
  * @param requestVotesReply
  */
case class RequestVotesReplyWrapper(requestVotesReply: RequestVotesReply){
  val creationDatetime: DateTime = new DateTime().withZone(DateTimeZone.UTC)

  override def toString: String = s"RequestVotesReplyWrapper: ${requestVotesReply}"
}

/**
  * Message from the internal Election to the external actor using it
  */
case object IAmLeader
case object IAmFollower

/**
  * The parent actor of the ElectionActor. We cannot simply use the context.parent as we have two different actor systems.
  * @param actorRef
  */
case class IAmTheParent(actorRef: ActorRef)

/**
  * Internal messages
  */
case object BecomeCandidate
case object BecomeFollower
case object BecomeLeader

/**
  * Order to send Ping messages to every nodes. Not used to discover nodes (more expensive).
  */
case object SendPingMessages

/**
  * Order to check that we received the Ping messages correctly in time
  */
case object CheckPingMessages

/**
  * Discover nodes for which we don't have any ActorRef
  */
case object DiscoverNodes

/**
  * The leader was lost, or there is currently no leader (when the process is starting up)
  */
case object AttemptElection

case object RequestVotesTimeoutCheck