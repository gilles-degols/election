package net.degols.filesgate.libs.election

import org.joda.time.{DateTime, DateTimeZone}

/**
  * Created by Gilles.Degols on 04-09-18.
  */
object Tools {
  def datetime(): DateTime = new DateTime().withZone(DateTimeZone.UTC)
}
