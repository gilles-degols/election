package net.degols.libs.election

import java.io.File

import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.LoggerFactory

import scala.util.Try

/**
  * Wrapper to automatically merge embedded configuration to be able to provide default values in our own library
  */
trait ConfigurationMerge {
  protected val logger = LoggerFactory.getLogger(getClass)
  val directoryName: String // directory of the project, so "election", "workflow", ...
  val defaultConfig: Config // Config injected by Guice

  /**
    * If the library is loaded directly as a subproject, the Config of the subproject overrides the configuration of the main
    * project by default, and we want the opposite.
    * Note: This seems to be fixed now... Better be careful with some breaking changes
    */
  protected lazy val projectConfig: Config = {
    val projectFile = new File(pathToProjectFile)
    ConfigFactory.load(ConfigFactory.parseFile(projectFile))
  }

  private val pathToProjectFile: String = {
    Try{ConfigFactory.systemProperties().getString("config.resource")}.getOrElse("conf/application.conf")
  }

  protected lazy val fallbackConfig: Config = {
    val fileInSubproject = new File(s"../$directoryName/src/main/resources/application.conf")
    val fileInProject = new File("main/resources/application.conf")
    if (fileInSubproject.exists()) {
      ConfigFactory.load(ConfigFactory.parseFile(fileInSubproject))
    } else {
      ConfigFactory.load(ConfigFactory.parseFile(fileInProject))
    }
  }
  lazy val config: Config = {
    // Ugly exception, but ease the debug in case something is wrong
    if(projectConfig == null) {
      throw new Exception("ProjectConfig is null")
    }
    if(defaultConfig == null) {
      throw new Exception("defaultConfig is null")
    }
    if(fallbackConfig == null) {
      throw new Exception("fallbackConfig is null")
    }

    logger.debug(s"ProjectConfig for election is ${Try{projectConfig.getConfig("election")}}")
    logger.debug(s"DefaultConfig for election is ${Try{defaultConfig.getConfig("election")}}")
    logger.debug(s"FallbackConfig for election is ${Try{fallbackConfig.getConfig("election")}}")
    projectConfig.withFallback(defaultConfig).withFallback(fallbackConfig)
  }
}
