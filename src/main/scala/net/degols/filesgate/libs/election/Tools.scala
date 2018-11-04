package net.degols.filesgate.libs.election

import akka.actor.ActorRef
import org.joda.time.{DateTime, DateTimeZone}

/**
  * Created by Gilles.Degols on 04-09-18.
  */
object Tools {
  def datetime(): DateTime = new DateTime().withZone(DateTimeZone.UTC)

  /**
    * Return the full path to a given actor ref
    * @param actorRef
    */
  def remoteActorPath(actorRef: ActorRef): String = akka.serialization.Serialization.serializedActorPath(actorRef).split("#").head

  def jvmIdFromActorRef(actorRef: ActorRef): String = remoteActorPath(actorRef).replace(".tcp","").replace(".udp","")

  def stacktraceToString(st: Throwable): String = ""
}
