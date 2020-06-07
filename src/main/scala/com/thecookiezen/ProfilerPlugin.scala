package com.thecookiezen

import java.nio.file.{Path, Paths}

import com.thecookiezen.ProfilerPlugin.PluginConfig
import com.thecookiezen.tools.{Logger, Profiling}

import scala.tools.nsc.plugins.{Plugin, PluginComponent}
import scala.tools.nsc.{Global, Phase}
import scala.util.matching.Regex

class ProfilerPlugin(val global: Global) extends Plugin {

  private final lazy val ShowProfiles = "show-profiles"
  private final lazy val SourceRoot = "sourceroot"
  private final lazy val PrintSearchResult = "print-search-result"
  private final lazy val GenerateMacroFlamegraph = "generate-macro-flamegraph"
  private final lazy val PrintFailedMacroImplicits = "print-failed-implicit-macro-candidates"
  private final lazy val ShowConcreteImplicitTparams = "show-concrete-implicit-tparams"
  private final lazy val PrintSearchRegex = s"$PrintSearchResult:(.*)".r
  private final lazy val SourceRootRegex = s"$SourceRoot:(.*)".r

  private final lazy val config = PluginConfig(
    super.options.contains(ShowProfiles),
    findOption(SourceRoot, SourceRootRegex).map(Paths.get(_)),
    findSearchIds(findOption(PrintSearchResult, PrintSearchRegex)),
    super.options.contains(GenerateMacroFlamegraph),
    super.options.contains(PrintFailedMacroImplicits),
    super.options.contains(ShowConcreteImplicitTparams)
  )

  lazy val implementation = new Profiling(ProfilerPlugin.this.global, config, logger)
  private lazy val logger = new Logger(global)

  val name = "profiler-plugin"

  override val optionsHelp: Option[String] = Some(s"""
       |-P:$name:${pad20(SourceRoot)}:_ Sets the source root for this project.
       |-P:$name:${pad20(ShowProfiles)} Logs profile information for every call-site.
       |-P:$name:${pad20(ShowConcreteImplicitTparams)} Shows types in flamegraphs of implicits with concrete type params.
       |-P:$name:${pad20(PrintSearchResult)}:_ Print implicit search result trees for a list of search ids separated by a comma.
    """.stripMargin)

  val description = "Profiles macros and implicit at the compilation time"

  val components = List[PluginComponent](ProfilerComponent)

  def findOption(name: String, pattern: Regex): Option[String] = {
    super.options.find(_.startsWith(name)).flatMap {
      case pattern(matched) => Some(matched)
      case _                => None
    }
  }

  def findSearchIds(userOption: Option[String]): Set[Int] = {
    userOption match {
      case Some(value) => value.split(",", Int.MaxValue).map(_.toInt).toSet
      case None        => Set.empty
    }
  }

  override def init(ops: List[String], e: (String) => Unit): Boolean = true

  private def pad20(option: String): String = option + (" " * (20 - option.length))

  implementation.registerProfilers()

  private object ProfilerComponent extends PluginComponent {
    lazy val globalOutputDir = new java.io.File(
        global.settings.outputDirs.getSingleOutput
          .map(_.file.getAbsolutePath)
          .getOrElse(global.settings.d.value)
      ).toPath

    override val global: implementation.global.type = implementation.global

    import scala.collection.mutable.LinkedHashMap
    override val phaseName: String = "scalac-profiling"
    override val runsAfter: List[String] = List("jvm")
    override val runsBefore: List[String] = List("terminal")

    override def newPhase(prev: Phase): Phase = {
      new StdPhase(prev) {
        override def apply(unit: global.CompilationUnit): Unit = ()

        override def run(): Unit = {
          super.run()
          val graphsDir = globalOutputDir.resolve(Paths.get("META-INF", "graphs"))
          reportStatistics(graphsDir)
        }
      }
    }

    private def reportStatistics(graphsPath: Path): Unit = {
      val macroProfiler = implementation.macroProfiler
      val persistedGraphData = implementation.generateGraphData(graphsPath)
      persistedGraphData.foreach(p => logger.info(s"Writing graph to $p"))

      if (config.showProfiles) {
        logger.info("Macro data per call-site", macroProfiler.perCallSite)
        logger.info("Macro data per file", macroProfiler.perFile)
        logger.info("Macro data in total", macroProfiler.inTotal)
        val expansions = macroProfiler.repeatedExpansions.map(showExpansion)
        logger.info("Macro repeated expansions", expansions)

        val macrosType = implementation.macrosByType.toList.sortBy(_._2)
        val macrosTypeLines = global.exitingTyper(macrosType.map(kv => kv._1.toString -> kv._2))

        logger.info("Macro expansions by type", toLinkedHashMap(macrosTypeLines))

        val implicitSearchesPosition = toLinkedHashMap(implementation.implicitSearchesByPos.toList.sortBy(_._2))

        logger.info("Implicit searches by position", implicitSearchesPosition)

        val sortedImplicitSearches = implementation.implicitSearchesByType.toList.sortBy(_._2)

        // Make sure to stringify types right after typer to avoid compiler crashes
        val stringifiedSearchCounter =
          global.exitingTyper(
            sortedImplicitSearches.map(kv => kv._1.toString -> kv._2)
          )
        logger.info("Implicit searches by type", toLinkedHashMap(stringifiedSearchCounter))
        ()
      }
    }

    private def showExpansion(expansion: (global.Tree, Int)): (String, Int) =
      global.showCode(expansion._1) -> expansion._2

    private def toLinkedHashMap[K, V](xs: List[(K, V)]): LinkedHashMap[K, V] = {
      val builder = LinkedHashMap.newBuilder[K, V]
      builder.++=(xs)
      builder.result()
    }
  }
}

object ProfilerPlugin {
  case class PluginConfig(
      showProfiles: Boolean,
      sourceRoot: Option[Path],
      printSearchIds: Set[Int],
      generateMacroFlamegraph: Boolean,
      printFailedMacroImplicits: Boolean,
      concreteTypeParamsInImplicits: Boolean
  )
}
