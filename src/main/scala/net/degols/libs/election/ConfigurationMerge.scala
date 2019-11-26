package net.degols.libs.election

import java.io.File
import java.nio.file.{Files, Paths}

import com.google.inject.ImplementedBy
import com.typesafe.config.{Config, ConfigFactory}
import org.slf4j.LoggerFactory

import scala.io.Source
import scala.util.{Failure, Success, Try}

/**
  * Wrapper to automatically merge embedded configuration to be able to provide default values in our own library
  * We do NOT trust the auto-inject configuration by play. It is not straight forward to understand how they decide
  * which configuration to load in it. It might just be a merge of multiple configuration together, but not in the
  * order that we would like, so we will rather always load the configuration ourselves.
  */
@ImplementedBy(classOf[ElectionConfigurationMerge])
trait ConfigurationMerge {
  protected val logger = LoggerFactory.getLogger(getClass)
  val filenames: Seq[String] // The different application...conf to merge together, in the order we wish to aggregate the configuration, so "election", "workflow", ... The first one overrides the other one

  /**
    * Paths external to the current JVM process for partial configuration overrides
    */
  protected lazy val externalConfigPaths: String = ""

  /**
    * Environment variable which can be used to to specify a specific list of files to load for partial config override
    */
  protected lazy val environmentVariableName: String = "EXTERNAL_CONFIGURATION"

  protected lazy val externalConfig: Config = {
    val paths = Option(System.getenv(environmentVariableName)).getOrElse(externalConfigPaths).split(';')
    paths.foldLeft(ConfigFactory.empty)((mergedConfig, p) => {
      val fallback = if (Files.exists(Paths.get(p))) {
        Try {
          val cfg = Source.fromFile(p).mkString
          ConfigFactory.load(ConfigFactory.parseString(cfg))
        } match {
          case Success(cfg) => cfg
          case Failure(err) =>
            logger.error(s"Failure to read partial config file override from the local file system: $p", err)
            throw err
        }
      } else {
        logger.info(s"No partial config file override found on the local file system: $p")
        ConfigFactory.empty()
      }
      mergedConfig.withFallback(fallback)
    })
  }


  /**
    * Configuration to the application.election.conf file, which overrides any fallback configuration
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
    filenames.foldLeft(ConfigFactory.empty)((mergedConfig, filename) => {
      // Should not be a big deal to load multiple times the same config as fallback. In any case, in a properly configured
      // system it should be okay
      val fallback = Try {
        Source.fromResource(filename).mkString
      } match {
        case Success(s) => ConfigFactory.load(ConfigFactory.parseString(s))
        case Failure(f) =>
          logger.error(s"Impossible to read resource file $filename for the ConfigurationMerge, we will re-throw the exception.")
          throw f
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

    externalConfig.withFallback(projectConfig).withFallback(fallbackConfig)
  }
}
