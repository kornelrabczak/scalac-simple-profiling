package com.thecookiezen.tools

import scala.collection.mutable
import scala.tools.nsc.Global
import scala.reflect.internal.util.Statistics
import java.lang.reflect.Field
import java.lang.Class

object QuantitiesHijacker {
  type Quantities = mutable.HashMap[String, Statistics#Quantity]
  def getRegisteredQuantities[G <: Global](global: G): Quantities = {
    val claszz = classOf[Statistics]
    
    
    val fields = claszz.getDeclaredFields()

    fields.foreach( field =>
      println(field.getName())
    )

    println("methods: ")

    val methods = claszz.getDeclaredMethods()

    methods.foreach( field =>
      println(field.getName())
    )

    val field = claszz.getDeclaredField("scala$reflect$internal$util$Statistics$$qs")
    field.setAccessible(true)
    field.get(global.statistics).asInstanceOf[Quantities]
  }
}
