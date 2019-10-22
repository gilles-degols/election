package net.degols.libs.election

import java.io.File

import com.google.inject.ImplementedBy
import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.LoggerFactory

import scala.util.Try

/**
  * Wrapper to automatically merge embedded configuration to be able to provide default values in our own library
  * We do NOT trust the auto-inject configuration by play. It is not straight forward to understand how they decide
  * which configuration to load in it. It might just be a merge of multiple configuration together, but not in the
  * order that we would like, so we will rather always load the configuration ourselves.
  */
@ImplementedBy(classOf[ElectionConfigurationMerge])
trait ConfigurationMerge {
  protected val logger = LoggerFactory.getLogger(getClass)
  val directories: Seq[String] // directory of the project, in the order we whish to aggregate the configuration, so "election", "workflow", ... The first one overrides the other one

  /**
    * Configuration to the application.conf file, which overrides any fallback configuration
    */
  protected lazy val projectConfig: Config = {
    val pathToProjectFile = Try{ConfigFactory.systemProperties().getString("config.resource")}.getOrElse("conf/application.conf")
    val projectFile = new File(pathToProjectFile)
    ConfigFactory.load(ConfigFactory.parseFile(projectFile))
  }

  /**
    * Merge multiple fallback configuration together
    */
  protected lazy val fallbackConfig: Config = {
    directories.foldLeft(ConfigFactory.empty)((mergedConfig, directory) => {
      // Should not be a big deal to load multiple times the same config as fallback. In any case, in a properly configured
      // system it should be okay
      val fileInSubproject = new File(s"../$directory/src/main/resources/application.conf")
      val fileInProject = new File("main/resources/application.conf")
      val fallback = if (fileInSubproject.exists()) {
        logger.debug(s"Create fallback config from $directory: file found, this is as sub-project")
        ConfigFactory.load(ConfigFactory.parseFile(fileInSubproject))
      } else {
        // If wrongly configured, this code means that we could have override in a wrong order
        logger.debug(s"Create fallback config from $directory: file not found, we assume we should load the project")
        ConfigFactory.load(ConfigFactory.parseFile(fileInProject))
      }
      mergedConfig.withFallback(fallback)
    })
  }

  lazy val config: Config = {
    // Ugly exception, but ease the debug in case something is wrong
    if(projectConfig == null) {
      throw new Exception("ProjectConfig is null")
    }
    if(fallbackConfig == null) {
      throw new Exception("fallbackConfig is null")
    }

    logger.debug(s"ProjectConfig for election is ${Try{projectConfig.getConfig("election")}}")
    logger.debug(s"FallbackConfig for election is ${Try{fallbackConfig.getConfig("election")}}")
    projectConfig.withFallback(fallbackConfig)
  }
}
