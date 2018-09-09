package net.degols.filesgate.libs.election

import scala.collection.mutable.ListBuffer

/**
  * Contain the history of a specific remote JVM (it will remain after a restart of the remote JVM)
  */
class JVMHistory(id: String) {
  private val _messages = ListBuffer.empty[RemoteMessageWrapper]

  def addMessage(message: RemoteMessageWrapper): Unit = {
    _messages.append(message)
    removeOldMessages()
  }

  private def removeOldMessages(): Unit = {
    if(_messages.length > 50) {
      val lastMessages = _messages.takeRight(10)
      _messages.clear()
      _messages.appendAll(lastMessages)
    }
  }

  def lastPingWrapper(): Option[RemoteMessageWrapper] = _messages.filter(_.remoteMessage.isInstanceOf[Ping]).lastOption
}
