package net.degols.filesgate.libs.election

/**
  * Created by Gilles.Degols on 27-08-18.
  */
class ElectionService {
  /**
    * History of every JVM, based on the jvm id
    */
  private var _jvm_histories: Map[String, JVMHistory] = Map.empty

  def addJvmMessage(remoteMessage: RemoteMessage): Unit = {
    val jvmHistory = _jvm_histories.get(remoteMessage.jvmId) match {
      case Some(history) => history
      case None =>
        _jvm_histories = _jvm_histories ++ Map(remoteMessage.jvmId -> new JVMHistory(remoteMessage.jvmId))
    }
  }

}
