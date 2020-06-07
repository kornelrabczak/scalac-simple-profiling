package com.thecookiezen.tools

import scala.reflect.internal.util.NoPosition

final class Logger[G <: scala.tools.nsc.Global](val global: G) {
  def info[T: pprint.TPrint](header: String, value: T): Unit = {
    val tokens = pprint.tokenize(value, height = 100000000).mkString
    info(s"$header:\n$tokens")
  }

  def info(msg: String): Unit = global.reporter.echo(NoPosition, msg)
}
