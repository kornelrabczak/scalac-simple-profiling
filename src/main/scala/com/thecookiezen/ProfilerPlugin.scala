package com.thecookiezen

import scala.tools.nsc.plugins.Plugin
import scala.tools.nsc.plugins.PluginComponent
import scala.tools.nsc.Global

class ProfilerPlugin(val global: Global) extends Plugin {

  val name = "basic"
  val description = "generates BASIC code"
  val components = List[PluginComponent](Component)

  private object Component extends PluginComponent {

    override val global: Global = ???

    override val phaseName: String = ???

    override val runsAfter: List[String] = ???

    override def newPhase(prev: scala.tools.nsc.Phase): scala.tools.nsc.Phase = ???

  }
}
