package net.degols.filesgate.libs.election

import javax.inject.Inject

import akka.actor.{ActorContext, ActorRef}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, TimeoutException}
import scala.util.{Failure, Success, Try, Random}

/**
  * Created by Gilles.Degols on 27-08-18.
  */
@Singleton
class ElectionService @Inject()(configurationService: ConfigurationService) {
  private val logger = LoggerFactory.getLogger(getClass)

  private val random = new Random(System.currentTimeMillis())

  /**
    * Every time we try to do an election attempt, we increment the seed id.
    */
  private var _lastElectionSeedId: Long = 0L

  /**
    * Last election seed that we sent
    */
  private var _lastElectionSeed: Option[ElectionSeed] = None

  /**
    * List of accepted answers for the _lastElectionSeed. Rejections are not saved, we rather cancel the _lastElectionSeed.
    */
  private var acceptingJvmIds: List[String] = List.empty[String]

  /**
    * Must be set by the ElectionActor
    */
  var context: ActorContext = _

  /**
    * ActorRef for each Jvm
    */
  var jvmActorRefs: Map[String, ActorRef] = Map.empty

  /**
    * Latest detected leader
    */
  var lastLeader: Option[ActorRef] = None

  /**
    * History of every JVM, based on the jvm id
    */
  private var _jvm_histories: Map[String, JVMHistory] = Map.empty

  /**
    * Every time we receive a jvm message, we add it to the history. The watcher is node on another level.
    * @param remoteMessage
    */
  def addJvmMessage(remoteMessage: RemoteMessage): Unit = {
    val jvmHistory = _jvm_histories.get(remoteMessage.jvmId) match {
      case Some(history) => history
      case None =>
        _jvm_histories = _jvm_histories ++ Map(remoteMessage.jvmId -> new JVMHistory(remoteMessage.jvmId))
        _jvm_histories(remoteMessage.jvmId)
    }

    jvmHistory.addMessage(remoteMessage)
  }

  /**
    * Retrieve the leader from the Ping messages
    */
  def leaderFromPings: Option[ActorRef] = {
    if(enoughReachableNodes) {
      val distinctLeaders = jvmActorRefs.flatMap(jvm => {
        _jvm_histories(jvm._1).lastPing()
      }).map(_.actorRef).toList.distinct

      if(distinctLeaders.isEmpty) {
        logger.debug("No leader received from external Ping messages, wait for the election.")
        None
      } else if(distinctLeaders.length == 1){
        logger.debug(s"Got a leader from the external nodes: ${distinctLeaders.head}")
        distinctLeaders.headOption
      } else {
        logger.error(s"We received Ping messages from some JVM giving a different last leader ($distinctLeaders). We cannot choose one for the moment.")
        None
      }
    } else {
      None
    }
  }

  /**
    * Add monitoring to a jvm, if it does not exist yet
    * @param jvmId
    * @param actorRef
    */
  def watchJvm(jvmId: String, actorRef: ActorRef): Unit = {
    jvmActorRefs.get(jvmId) match {
      case None =>
        // The watcher could fail if it is not available anymore
        Try{context.watch(actorRef)} match {
          case Success(res) =>
            jvmActorRefs = jvmActorRefs ++ Map(jvmId -> actorRef)
          case Failure(err) =>
            logger.warn("Failed to put a watcher on a jvm, it probably died during the process.")
        }

      case Some(res) =>
      // Nothing to do, already watching
    }
  }

  /**
    * Remove the watching of a jvm, typically used when we received a Terminated message
    */
  def unwatchJvm(actorRef: ActorRef): Unit = {
    jvmIdForActorRef(actorRef) match {
      case Some(res) => jvmActorRefs = jvmActorRefs.filter(_._1 != res)
      case None => logger.error(s"Trying to unwatch a jvm ($actorRef) already not watched anymore...")
    }
  }

  /**
    * Send a ping to every actor, reachable or not.
    */
  def sendPingToAllNodes(): Unit = {
    configurationService.electionNodes.foreach(electionNode => {
      actorRefForElectionNode(electionNode) match {
        case Some(res) => // Quite simple to contact it
          logger.debug(s"Send ping to reachable ElectionNode: $electionNode")
          res ! Ping(context.self, lastLeader)
        case None => // Need to resolve the actor path. It might not exist, we are not sure
          logger.debug(s"Send ping to previously unreachable ElectionNode: $electionNode")
          sendPingToUnreachableNode(electionNode)
      }
    })
  }

  /**
    * Try to send a ping to an unreachable node. To avoid locking the system, this is asynchronous.
    * @param electionNode
    */
  private def sendPingToUnreachableNode(electionNode: ElectionNode): Future[Try[Unit]] = Future {
    Try {
      val remoteActorRef: ActorRef = try {
        Await.result(context.actorSelection(electionNode.akkaUri).resolveOne(configurationService.timeoutUnreachableNode).recover {
          case t: TimeoutException =>
            logger.warn("Got a TimeoutException while trying to resolve the actorRef of an ElectionNode.")
            null
          case x: Exception =>
            logger.warn("Got a generic Exception while trying to resolve the actorRef of an ElectionNode.")
            x.printStackTrace()
            null
        }, configurationService.timeoutUnreachableNode)
      } catch {
        case x: TimeoutException =>
          logger.warn("Got a TimeoutException while trying to resolve the actorRef of an ElectionNode.")
          null
        case x: Throwable =>
          logger.warn("Got a generic Exception while trying to resolve the actorRef of an ElectionNode.")
          x.printStackTrace()
          null
      }

      if(remoteActorRef != null) {
        remoteActorRef ! Ping(context.self, lastLeader)
      }
    }
  }

  /**
    * Attempt an election: it sends an ElectionSeed message to every reachable node. If we receive replies fast enough acknowledging
    * it, the current node will be elected.
    */
  def sendElectionSeeds(): Unit = {
    if(!enoughReachableNodes) {
      throw new Exception("ElectionSeeds must only be sent if we have enough reachable nodes!")
    }

    // Lottery to try to be leader
    _lastElectionSeedId += 1L
    val seed = random.nextLong()
    _lastElectionSeed = Option(ElectionSeed(context.self, seed, _lastElectionSeedId))
    acceptingJvmIds = List.empty[String]

    configurationService.electionNodes.foreach(electionNode => {
      actorRefForElectionNode(electionNode) match {
        case Some(res) => // Quite simple to contact it
          logger.debug(s"Send ElectionSeed (${_lastElectionSeed.get}) to reachable ElectionNode: $electionNode")
          res ! _lastElectionSeed.get
        case None => // Unreachabled node, we do nothing.
      }
    })
  }

  /**
    * Give the reply for a given electionSeed.
    */
  def replyToElectionSeed(electionSeed: ElectionSeed): Either[ElectionSeedAccepted, ElectionSeedRefused] = {
    // Find last ping of the external node, to roughly estimate the time difference and verify if a new seed should have already
    // been generated
    val lastPing = _jvm_histories.get(electionSeed.jvmId) match {
      case Some(history) => history.lastPing()
      case None => None
    }

    if(lastLeader.isDefined) {
      Right(ElectionSeedRefused(context.self, electionSeed, s"Already got a leader: ${lastLeader.get}."))
    } else if(lastPing.isEmpty) {
      Right(ElectionSeedRefused(context.self, electionSeed, s"No last ping from the node sending the ElectionSeed."))
    } else if(lastPing.get.creationDatetime.getMillis - electionSeed.creationDatetime.getMillis > configurationService.electionAttemptFrequency.toMillis) {
      val timeDifference = lastPing.get.creationDatetime.getMillis - electionSeed.creationDatetime.getMillis
      Right(ElectionSeedRefused(context.self, electionSeed, s"Time difference between last ping and election seed is bigger than the seed frequency. This is an old message, we won't process it. Time difference: $timeDifference ms > ${configurationService.electionAttemptFrequency.toMillis} ms"))
    } else if(math.abs(electionSeed.creationDatetime.getMillis - Tools.datetime().getMillis) > configurationService.maximumTimeDifferenceBetweenNodes.toMillis) {
      val timeDifference = electionSeed.creationDatetime.getMillis - electionSeed.creationDatetime.getMillis
      Right(ElectionSeedRefused(context.self, electionSeed, s"Time difference between election seed and current node is bigger than the allowed time difference between nodes. We won't allow the election. Time difference: $timeDifference ms > ${configurationService.maximumTimeDifferenceBetweenNodes.toMillis} ms"))
    } else if(_lastElectionSeed.isDefined && electionSeed.jvmId != currentJvmId && electionSeed.seed < _lastElectionSeed.get.seed) {
      Right(ElectionSeedRefused(context.self, electionSeed, s"Smaller seed than we one we generated lastly: ${electionSeed.seed} < ${_lastElectionSeed.get.seed}"))
    } else {
      Left(ElectionSeedAccepted(context.self, electionSeed))
    }
  }

  /**
    * Handle the reply of an ElectionSeed we sent. Return true if we become leader.
    */
  def becomeLeaderWithReplyFromElectionSeed(electionSeedResult: ElectionSeedReply): Boolean = {
    if(lastLeader.isDefined) {
      logger.debug(s"A leader has already been defined ($lastLeader), the reply from the external node does not really matter.")
    } else if(electionSeedResult.electionSeed.id != _lastElectionSeedId) {
      logger.debug(s"Got result for an obsolete ElectionSeed message: ${electionSeedResult.electionSeed.id} != ${_lastElectionSeedId}. We cannot accept this message.")
    } else if(_lastElectionSeed.isEmpty) {
      logger.debug(s"The reply is for an election seed we removed as another node refused it.")
    } else if(electionSeedResult.isInstanceOf[ElectionSeedRefused]) {
      logger.debug("Got one rejection for the election seed, we cannnot be leader for now.")
      _lastElectionSeed = None
      acceptingJvmIds = List.empty[String]
    } else if(electionSeedResult.isInstanceOf[ElectionSeedAccepted]) {
      logger.debug("Got a reply accepting our seed, store it.")
      acceptingJvmIds = (acceptingJvmIds :+ electionSeedResult.jvmId).distinct // Just for security we remove duplicate entries. This should never happen anyway.
    } else{
      logger.error(s"Weird, we received an ElectionSeedResult ($electionSeedResult) with an unsupported type.")
    }

    // If enough replies, we might decide to become leader
    if(acceptingJvmIds.length >= nodesForMajority) {
      logger.debug(s"We got ${acceptingJvmIds.length} ElectionSeedAccepted messages, we can become primary.")
      lastLeader = Option(context.self)
      true
    } else {
      false
    }
  }

  /**
    * Indicate if we have a majority of nodes running
    */
  def enoughReachableNodes: Boolean = {
    jvmActorRefs.values.size > configurationService.electionNodes.length / 2f
  }

  /**
    * Can we reach all nodes in the configuration?
    * @return
    */
  def allReachableNodes: Boolean = {
    jvmActorRefs.values.size == configurationService.electionNodes.length
  }

  /**
    * Number of nodes we should have replies from to have a majority
    */
  def nodesForMajority: Int = math.ceil(configurationService.electionNodes.length / 2f).toInt

  /**
    * Try to find the jvm id for a given actor ref. Normally we should always have it.
    */
  def jvmIdForActorRef(actorRef: ActorRef): Option[String] = {
    jvmActorRefs.find(_._2.toString() == actorRef.toString()) match {
      case Some(res) => Option(res._1)
      case None => None
    }
  }

  /**
    * Return the actor ref for a given ElectionNode, if it exists.
    */
  def actorRefForElectionNode(electionNode: ElectionNode): Option[ActorRef] = {
    jvmActorRefs.find(_._2.toString() == electionNode.akkaUri) match {
      case Some(res) => Option(res._2)
      case None => None
    }
  }

  /**
    * We only allow messages from machines with an ip / hostname in the configuration
    */
  def actorRefInConfig(actorRef: ActorRef): Boolean = {
    val isInConfig = configurationService.electionNodes.exists(electionNode => {
      logger.debug(s"Comparing election node with actor ref: ${electionNode.akkaUri} vs ${actorRef.path.toString}")
      electionNode.akkaUri == actorRef.path.toString
    })

    if(!isInConfig) {
      logger.warn(s"We received a message from an actorRef (${actorRef.path.toString} not in the configured nodes: ${configurationService.electionNodes}")
    }

    isInConfig
  }

  /**
    * Return the current jvmId
    */
  def currentJvmId: String = new RemoteMessage(context.self).jvmId
}
