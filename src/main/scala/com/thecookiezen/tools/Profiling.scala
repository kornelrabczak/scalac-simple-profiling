package com.thecookiezen.tools

import java.nio.file.{Files, Path, StandardOpenOption}

import scala.tools.nsc.Global
import scala.reflect.internal.util.StatisticsStatics
import scala.collection.mutable
import com.thecookiezen.ProfilerPlugin.PluginConfig
import com.thecookiezen.tools.Profiling.MacroInfo
import pprint.TPrint
import com.thecookiezen.metrics.Timer
import com.thecookiezen.metrics.Timer.TimerSnapshot

import scala.jdk.CollectionConverters._

final class Profiling[G <: Global](override val global: G, config: PluginConfig, logger: Logger[G]) extends ProfilingStats {
  import global._

  def registerProfilers(): Unit = {
    // Register our profiling macro plugin
    analyzer.addMacroPlugin(ProfilingMacroPlugin)
    analyzer.addAnalyzerPlugin(ProfilingAnalyzerPlugin)
  }

  import scala.reflect.internal.util.SourceFile

  case class MacroProfiler(
      perCallSite: Map[Position, MacroInfo],
      perFile: Map[SourceFile, MacroInfo],
      inTotal: MacroInfo
  )

  def toMillis(nanos: Long): Long =
    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(nanos)

  def groupPerFile[V](kvs: Map[Position, V])(empty: V, aggregate: (V, V) => V): Map[SourceFile, V] = {
    kvs
        .groupBy(_._1.source)
        .view
        .mapValues {
          posInfos: Map[Position, V] => posInfos.valuesIterator.fold(empty)(aggregate)
        }.toMap
  }

  lazy val macroProfiler: MacroProfiler = {
    import ProfilingMacroPlugin.macroInfos
    val perCallSite = macroInfos.toMap

    val perFile = groupPerFile(perCallSite)(MacroInfo.Empty, _ + _)
      .view
      .mapValues(i => i.copy(expansionNanos = toMillis(i.expansionNanos)))
      .toMap

      val inTotal = MacroInfo.aggregate(perFile.valuesIterator)

    val callSiteNanos = perCallSite
      .view
      .mapValues(i => i.copy(expansionNanos = toMillis(i.expansionNanos)))
      .toMap

    MacroProfiler(callSiteNanos, perFile, inTotal)
  }

  case class ImplicitInfo(count: Int) {
    def +(other: ImplicitInfo): ImplicitInfo = ImplicitInfo(count + other.count)
  }

  object ImplicitInfo {
    final val Empty = ImplicitInfo(0)
    def aggregate(infos: Iterator[ImplicitInfo]): ImplicitInfo = infos.fold(Empty)(_ + _)
    implicit val infoOrdering: Ordering[ImplicitInfo] = Ordering.by(_.count)
  }

  case class ImplicitProfiler(
      perCallSite: Map[Position, ImplicitInfo],
      perFile: Map[SourceFile, ImplicitInfo],
      perType: Map[Type, ImplicitInfo],
      inTotal: ImplicitInfo
  )

  lazy val implicitProfiler: ImplicitProfiler = {
    val perCallSite = implicitSearchesByPos.view.mapValues(ImplicitInfo.apply).toMap
    val perFile = groupPerFile[ImplicitInfo](perCallSite)(ImplicitInfo.Empty, _ + _)
    val perType = implicitSearchesByType.view.mapValues(ImplicitInfo.apply).toMap
    val inTotal = ImplicitInfo.aggregate(perFile.valuesIterator)
    ImplicitProfiler(perCallSite, perFile, perType, inTotal)
  }

  // Copied from `TypeDiagnostics` to have expanded types in implicit search
  private object DealiasedType extends TypeMap {
    def apply(tp: Type): Type = tp match {
      case TypeRef(_, sym, _) if sym.isAliasType && !sym.isInDefaultNamespace =>
        mapOver(tp.dealias)
      case _ => mapOver(tp)
    }
  }

  def concreteTypeFromSearch(tree: Tree, default: Type): Type = {
    tree match {
      case EmptyTree => default
      case Block(_, expr) => expr.tpe
      case Try(block, _, _) =>
        block match {
          case Block(_, expr) => expr.tpe
          case t => t.tpe
        }
      case t =>
        val treeType = t.tpe
        if (treeType == null || treeType == NoType) default else treeType
    }
  }

  def generateGraphData(outputDir: Path): List[Path] = {
    Files.createDirectories(outputDir)
    val randomId = java.lang.Long.toString(System.currentTimeMillis())
    val implicitGraphName = s"implicit-searches-$randomId"
    val macroGraphName = s"macros-$randomId"
    val implicitFlamegraphFile = outputDir.resolve(s"$implicitGraphName.flamegraph")
    val implicits = ProfilingAnalyzerPlugin.getImplicitStacks
    Files.write(implicitFlamegraphFile, implicits.asJava, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
    if (config.generateMacroFlamegraph) {
      val macroFlamegraphFile = outputDir.resolve(s"$macroGraphName.flamegraph")
      val macroStacks = ProfilingMacroPlugin.getMacroStacks
      Files.write(macroFlamegraphFile, macroStacks.asJava, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
      List(implicitFlamegraphFile, macroFlamegraphFile)
    } else List(implicitFlamegraphFile)
  }

  private def typeToString(`type`: Type): String =
    global.exitingTyper(`type`.toLongString).trim

  // Moving this here so that it's accessible to the macro plugin
  private type Entry = (global.analyzer.ImplicitSearch, TimerSnapshot, TimerSnapshot)
  private var implicitsStack: List[Entry] = Nil

  object FoldableStack {
    def fold(name: String)(names: mutable.Map[Int, List[String]], times: mutable.Map[Int, Long]): mutable.Seq[String] = {
      val stacks = mutable.Buffer[String]()

      times.foreach {
        case (id, nanos) =>
          val stackNames = names.getOrElse(id, sys.error(s"Stack name for $name id $id doesn't exist!"))
          val stackName = stackNames.mkString(";")
          stacks += s"$stackName ${nanos / 1000}"
      }

      stacks.sorted
    }
  }

  private object ProfilingAnalyzerPlugin extends global.analyzer.AnalyzerPlugin {
    private val implicitsTimers = perRunCaches.newAnyRefMap[Type, Timer]()
    private val stackedNanos = perRunCaches.newMap[Int, Long]()
    private val stackedNames = perRunCaches.newMap[Int, List[String]]()
    private val searchIdsToTimers = perRunCaches.newMap[Int, Timer]()
    private val searchIdChildren = perRunCaches.newMap[Int, List[analyzer.ImplicitSearch]]()

    def getImplicitStacks: mutable.Seq[String] = FoldableStack.fold("search")(stackedNames, stackedNanos)

    private def getImplicitTimerFor(candidate: Type): Timer =
      implicitsTimers.getOrElse(candidate, sys.error(s"Timer for $candidate doesn't exist"))

    private def getSearchTimerFor(searchId: Int): Timer = {
      searchIdsToTimers.getOrElse(searchId, sys.error(s"Missing non-cumulative timer for $searchId"))
    }

    override def pluginsNotifyImplicitSearch(search: global.analyzer.ImplicitSearch): Unit = {
      if (StatisticsStatics.areSomeColdStatsEnabled() && statistics.areStatisticsLocallyEnabled) {
        val targetType = search.pt
        val targetPos = search.pos

        // Stop counter of dependant implicit search
        implicitsStack.headOption.foreach {
          case (search, _, searchStart) => getSearchTimerFor(search.searchId).stop(searchStart)
        }

        // We add ourselves to the child list of our parent implicit search
        implicitsStack.headOption match {
          case Some((prevSearch, _, _)) =>
            val prevId = prevSearch.searchId
            val prevChilds = searchIdChildren.getOrElse(prevId, Nil)
            searchIdChildren.update(prevId, search :: prevChilds)
          case None => ()
        }

        // Create timer
        val prefix = s"$targetType"
        val perTypeTimer = implicitsTimers.getOrElseUpdate(targetType, Timer(prefix))

        // Create non-cumulative timer for the search
        val searchId = search.searchId
        val searchPrefix = s"implicit search $searchId"
        val searchTimer = Timer(searchPrefix)
        searchIdsToTimers.+=(searchId -> searchTimer)

        // Start the timer as soon as possible
        val implicitTypeStart = perTypeTimer.start
        val searchStart = searchTimer.start

        // Update all timers and counters
        val typeCounter = implicitSearchesByType.getOrElse(targetType, 0)
        implicitSearchesByType.update(targetType, typeCounter + 1)
        val posCounter = implicitSearchesByPos.getOrElse(targetPos, 0)
        implicitSearchesByPos.update(targetPos, posCounter + 1)
        if (global.analyzer.openMacros.nonEmpty)
          statistics.incCounter(implicitSearchesByMacrosCount)

        implicitsStack = (search, implicitTypeStart, searchStart) :: implicitsStack
      }
    }

    override def pluginsNotifyImplicitSearchResult(result: global.analyzer.SearchResult): Unit = {
      super.pluginsNotifyImplicitSearchResult(result)
      if (StatisticsStatics.areSomeColdStatsEnabled() && statistics.areStatisticsLocallyEnabled) {
        // 1. Get timer of the running search
        val (search, implicitTypeStart, searchStart) = implicitsStack.head
        val targetType = search.pt
        val timer = getImplicitTimerFor(targetType)

        // 2. Register the timing diff for every stacked name.
        def stopTimerFlamegraph(prev: Option[analyzer.ImplicitSearch]): Unit = {
          val searchId = search.searchId
          def missing(name: String): Nothing =
            sys.error(s"Missing $name for $searchId ($targetType).")

          val forcedExpansions = ProfilingMacroPlugin.searchIdsToMacroStates.getOrElse(searchId, Nil)
          val expandedStr = s"(expanded macros ${forcedExpansions.size})"

          // Detect macro name if the type we get comes from a macro to add it to the stack
          val suffix = {
            val errorTag = if (result.isFailure) " _[j]" else ""
            result.tree.attachments.get[analyzer.MacroExpansionAttachment] match {
              case Some(analyzer.MacroExpansionAttachment(expandee: Tree, _)) =>
                val expandeeSymbol = treeInfo.dissectApplied(expandee).core.symbol
                analyzer.loadMacroImplBinding(expandeeSymbol) match {
                  case Some(a) =>
                    val l = if (errorTag.isEmpty) " _[i]" else errorTag
                    s" (id $searchId) $expandedStr (tree from `${a.className}.${a.methName}`)$l"
                  case None => s" $expandedStr $errorTag"
                }
              case None => s" $expandedStr $errorTag"
            }
          }

          // Complete stack names of triggered implicit searches
          val children = searchIdChildren.getOrElse(searchId, Nil)
          prev.foreach { p =>
            val current = searchIdChildren.getOrElse(p.searchId, Nil)
            searchIdChildren.update(p.searchId, children ::: current)
          }

          val typeForStack = DealiasedType {
            if (!config.concreteTypeParamsInImplicits) targetType
            else concreteTypeFromSearch(result.subst(result.tree), targetType)
          }

          if (config.printSearchIds.contains(searchId) || (result.isFailure && config.printFailedMacroImplicits)) {
            logger.info(
              s"""implicit search $searchId:
                 |  -> valid ${result.isSuccess}
                 |  -> type `$typeForStack`
                 |  -> ${search.undet_s}
                 |  -> ${search.ctx_s}
                 |  -> tree:
                 |${showCode(result.tree)}
                 |  -> forced expansions:
                 |${forcedExpansions.mkString("  ", "  \n", "\n")}
                 |""".stripMargin
            )
          }

          val thisStackName = s"${typeToString(typeForStack)}$suffix"
          stackedNames.update(searchId, List(thisStackName))
          children.foreach { childSearch =>
            val id = childSearch.searchId
            val childrenStackName = stackedNames.getOrElse(id, missing("stack name"))
            stackedNames.update(id, thisStackName :: childrenStackName)
          }

          // Save the nanos for this implicit search
          val searchTimer = getSearchTimerFor(searchId)
          searchTimer.stop(searchStart)
          val previousNanos = stackedNanos.getOrElse(searchId, 0L)
          stackedNanos.+=((searchId, searchTimer.nanos + previousNanos))
        }

        // 3. Reset the stack and stop timer if there is a dependant search
        val previousImplicits = implicitsStack.tail
        implicitsStack = previousImplicits.headOption match {
          case Some((prevSearch, prevImplicitTypeStart, _)) =>
            stopTimerFlamegraph(Some(prevSearch))
            timer.stop(implicitTypeStart)
            val newPrevStart = getSearchTimerFor(prevSearch.searchId).start
            (prevSearch, prevImplicitTypeStart, newPrevStart) :: previousImplicits.tail
          case None =>
            stopTimerFlamegraph(None)
            timer.stop(implicitTypeStart)
            previousImplicits
        }
      }
    }
  }

  sealed trait MacroState {
    def pt: Type
    def tree: Tree
  }

  case class DelayedMacro(pt: Type, tree: Tree) extends MacroState
  case class SkippedMacro(pt: Type, tree: Tree) extends MacroState
  case class SuppressedMacro(pt: Type, tree: Tree) extends MacroState
  case class FallbackMacro(pt: Type, tree: Tree) extends MacroState
  case class FailedMacro(pt: Type, tree: Tree) extends MacroState
  case class SucceededMacro(pt: Type, tree: Tree) extends MacroState

  case class MacroEntry(
      id: Int,
      originalPt: Type,
      start: TimerSnapshot,
      state: Option[MacroState]
  )

  private var macrosStack: List[MacroEntry] = Nil
  private var macroCounter: Int = 0

  object ProfilingMacroPlugin extends global.analyzer.MacroPlugin {
    type Typer = analyzer.Typer

    type RepeatedKey = (String, String)

    val macroInfos = perRunCaches.newAnyRefMap[Position, MacroInfo]
    val searchIdsToMacroStates = perRunCaches.newMap[Int, List[MacroState]]
    private val macroIdsToTimers = perRunCaches.newMap[Int, Timer]()
    private val macroChildren = perRunCaches.newMap[Int, List[MacroEntry]]()
    private val stackedNanos = perRunCaches.newMap[Int, Long]()
    private val stackedNames = perRunCaches.newMap[Int, List[String]]()

    def getMacroStacks = FoldableStack.fold("macro")(stackedNames, stackedNanos)

    import scala.tools.nsc.Mode
    override def pluginsMacroExpand(t: Typer, expandee: Tree, md: Mode, pt: Type): Option[Tree] = {
      val macroId = macroCounter
      macroCounter = macroCounter + 1

      object expander extends analyzer.DefMacroExpander(t, expandee, md, pt) {

        /** The default method that expands all macros. */
        override def apply(desugared: Tree): Tree = {
          val prevData = macrosStack.headOption.map { prev =>
            macroIdsToTimers.getOrElse(
              prev.id,
              sys.error(s"fatal error: missing timer for ${prev.id}")
            ) -> prev
          }

          // Let's first stop the previous timer to have consistent times for the flamegraph
          prevData.foreach {
            case (prevTimer, prev) => prevTimer.stop(prev.start)
          }

          // Let's create our own timer
          val macroTimer = Timer(s"macro $macroId")
          macroIdsToTimers += ((macroId, macroTimer))
          val start = macroTimer.start

          val entry = MacroEntry(macroId, pt, start, None)

          if (config.generateMacroFlamegraph) {
            // We add ourselves to the child list of our parent macro
            prevData.foreach {
              case (_, entry) =>
                val prevId = entry.id
                val prevChilds = macroChildren.getOrElse(prevId, Nil)
                macroChildren.update(prevId, entry :: prevChilds)
            }
          }

          macrosStack = entry :: macrosStack
          try super.apply(desugared)
          finally {
            val children = macroChildren.getOrElse(macroId, Nil)
            if (config.generateMacroFlamegraph) {
              // Complete stack names of triggered implicit searches
              prevData.foreach {
                case (_, p) =>
                  val prevChildren = macroChildren.getOrElse(p.id, Nil)
                  macroChildren.update(p.id, children ::: prevChildren)
              }
            }

            // We need to fetch the entry from the stack as it can be modified
            val parents = macrosStack.tail
            macrosStack.headOption match {
              case Some(head) =>
                if (config.generateMacroFlamegraph) {
                  val thisStackName = head.state match {
                    case Some(FailedMacro(pt, _)) => s"${typeToString(pt)} [failed]"
                    case Some(DelayedMacro(pt, _)) => s"${typeToString(pt)} [delayed]"
                    case Some(SucceededMacro(pt, _)) => s"${typeToString(pt)}"
                    case Some(SuppressedMacro(pt, _)) => s"${typeToString(pt)} [suppressed]"
                    case Some(SkippedMacro(pt, _)) => s"${typeToString(pt)} [skipped]"
                    case Some(FallbackMacro(pt, _)) => s"${typeToString(pt)} [fallback]"
                    case None => sys.error("Fatal error: macro has no state!")
                  }

                  stackedNames.update(macroId, thisStackName :: Nil)
                  children.foreach { childSearch =>
                    val id = childSearch.id
                    val childrenStackName = stackedNames.getOrElse(id, sys.error("no stack name"))
                    stackedNames.update(id, thisStackName :: childrenStackName)
                  }
                }

                macroTimer.stop(head.start)
                val previousNanos = stackedNanos.getOrElse(macroId, 0L)

                // Updates expansionNanos time after super.apply() for MacroInfo at the specified position
                val nanosPassed = macroTimer.nanos + previousNanos
                macroInfos.get(desugared.pos).foreach { oldMacroInfo =>
                  macroInfos.update(desugared.pos, oldMacroInfo.copy(expansionNanos = nanosPassed))
                }

                stackedNanos.+=((macroId, nanosPassed))
                prevData match {
                  case Some((prevTimer, prev)) =>
                    // Let's restart the timer of the previous macro expansion
                    val newStart = prevTimer.start
                    // prev is the head of `parents`, so let's replace it on stack with the new start
                    macrosStack = prev.copy(start = newStart) :: parents.tail
                  case None => macrosStack = parents
                }
              case None => sys.error(s"fatal error: expected macro entry for macro id $macroId")
            }
          }
        }

        def mapToCurrentImplicitSearch(exp: MacroState): Unit = {
          implicitsStack.headOption match {
            case Some(i) =>
              val id = i._1.searchId
              val currentMacros = searchIdsToMacroStates.getOrElse(id, Nil)
              searchIdsToMacroStates.update(id, exp :: currentMacros)
            case None => ()
          }
        }

        def updateStack(state: MacroState): Unit = {
          macrosStack.headOption match {
            case Some(entry) =>
              macrosStack = entry.copy(state = Some(state)) :: macrosStack.tail
            case None => sys.error("fatal error: stack cannot be empty while updating!")
          }
        }

        override def onFailure(expanded: Tree) = {
          val state = FailedMacro(pt, expanded)
          mapToCurrentImplicitSearch(state)
          statistics.incCounter(failedMacros)
          updateStack(state)
          super.onFailure(expanded)
        }

        override def onSkipped(expanded: Tree) = {
          val state = SkippedMacro(pt, expanded)
          mapToCurrentImplicitSearch(state)
          statistics.incCounter(skippedMacros)
          updateStack(state)
          super.onDelayed(expanded)
        }

        override def onFallback(expanded: Tree) = {
          val state = FallbackMacro(pt, expanded)
          mapToCurrentImplicitSearch(state)
          statistics.incCounter(fallbackMacros)
          updateStack(state)
          super.onFallback(expanded)
        }

        override def onSuppressed(expanded: Tree) = {
          val state = SuppressedMacro(pt, expanded)
          mapToCurrentImplicitSearch(state)
          statistics.incCounter(suppressedMacros)
          updateStack(state)
          super.onSuppressed(expanded)
        }

        override def onDelayed(expanded: Tree) = {
          val state = DelayedMacro(pt, expanded)
          mapToCurrentImplicitSearch(state)
          statistics.incCounter(delayedMacros)
          updateStack(state)
          super.onDelayed(expanded)
        }

        override def onSuccess(expanded0: Tree) = {
          val expanded = super.onSuccess(expanded0)
          val expandedType = concreteTypeFromSearch(expanded, pt)
          val state = SucceededMacro(expandedType, expanded)
          mapToCurrentImplicitSearch(state)
          updateStack(state)

          // Update macro counter per type returned
          val macroTypeCounter = macrosByType.getOrElse(expandedType, 0)
          macrosByType.update(expandedType, macroTypeCounter + 1)

          val callSitePos = expandee.pos
          val macroInfo = macroInfos.getOrElse(callSitePos, MacroInfo.Empty)
          val expandedMacros = macroInfo.expandedMacros + 1

          // Use 0L for the timer because it will be filled in by the caller `apply`
          macroInfos.put(callSitePos, MacroInfo(expandedMacros, 0, 0L))
          expanded
        }
      }
      Some(expander(expandee))
    }
  }
}

trait ProfilingStats {
  val global: Global
  import global.statistics.{newSubCounter, macroExpandCount, implicitSearchCount}
  macroExpandCount.children.clear()

  final val failedMacros = newSubCounter("  of which failed macros", macroExpandCount)
  final val delayedMacros = newSubCounter("  of which delayed macros", macroExpandCount)
  final val suppressedMacros = newSubCounter("  of which suppressed macros", macroExpandCount)
  final val fallbackMacros = newSubCounter("  of which fallback macros", macroExpandCount)
  final val skippedMacros = newSubCounter("  of which skipped macros", macroExpandCount)
  final val implicitSearchesByMacrosCount = newSubCounter("  from macros", implicitSearchCount)

  import scala.reflect.internal.util.Position
  final val macrosByType = new scala.collection.mutable.HashMap[global.Type, Int]()
  final val implicitSearchesByType = global.perRunCaches.newMap[global.Type, Int]()
  final val implicitSearchesByPos = global.perRunCaches.newMap[Position, Int]()
}

object Profiling {
  /**
   * Represents the profiling information about expanded macros.
   *
   * Note that we could derive the value of expanded macros from the
   * number of instances of [[MacroInfo]] if it were not by the fact
   * that a macro can expand in the same position more than once. We
   * want to be able to report/analyse such cases on their own, so
   * we keep it as a paramater of this entity.
   */
  case class MacroInfo(expandedMacros: Int, expandedNodes: Int, expansionNanos: Long) {
    def +(other: MacroInfo): MacroInfo = {
      val totalExpanded = expandedMacros + other.expandedMacros
      val totalNodes = expandedNodes + other.expandedNodes
      val totalTime = expansionNanos + other.expansionNanos
      MacroInfo(totalExpanded, totalNodes, totalTime)
    }
  }

  object MacroInfo {
    final val Empty = MacroInfo(0, 0, 0L)
    implicit val macroInfoOrdering: Ordering[MacroInfo] = Ordering.by(_.expansionNanos)
    def aggregate(infos: Iterator[MacroInfo]): MacroInfo = {
      infos.foldLeft(MacroInfo.Empty)(_ + _)
    }

    implicit val intPrint: TPrint[Int] = TPrint.default[Int]
    implicit val stringPrint: TPrint[String] = TPrint.default[String]
    implicit val macroInfoPrint: TPrint[MacroInfo] = TPrint.default[MacroInfo]
  }
}