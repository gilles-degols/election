package net.degols.filesgate.libs.election

import scala.collection.mutable.ListBuffer

/**
  * Contain the history of a specific remote JVM (it will remain after a restart of the remote JVM)
  */
class JVMHistory(id: String) {
  private val _messages = ListBuffer.empty[RemoteMessage]

  def addMessage(message: RemoteMessage): Unit = _messages.append(message)
}
