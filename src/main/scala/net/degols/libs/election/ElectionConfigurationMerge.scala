package net.degols.libs.election
import javax.inject.Singleton

@Singleton
class ElectionConfigurationMerge extends ConfigurationMerge {
  override val filenames: Seq[String] = Seq("application.election.conf")
}
