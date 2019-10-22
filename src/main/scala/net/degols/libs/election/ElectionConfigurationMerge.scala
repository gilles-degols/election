package net.degols.libs.election
import javax.inject.Singleton

@Singleton
class ElectionConfigurationMerge extends ConfigurationMerge {
  override val directories: Seq[String] = Seq("election")
}
