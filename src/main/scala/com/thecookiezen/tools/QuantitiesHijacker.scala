package com.thecookiezen.tools

import scala.collection.mutable
import scala.reflect.internal.util.Statistics
import scala.tools.nsc.Global

object QuantitiesHijacker {
  type Quantities = mutable.HashMap[String, Statistics#Quantity]
  def getRegisteredQuantities[G <: Global](global: G): Quantities = {
    val field = classOf[Statistics].getDeclaredField("scala$reflect$internal$util$Statistics$$qs")
    field.setAccessible(true)
    field.get(global.statistics).asInstanceOf[Quantities]
  }
}
