package com.thecookiezen.metrics

import java.util.concurrent.atomic.AtomicLong

import com.thecookiezen.metrics.Timer.TimerSnapshot

import scala.runtime.LongRef

class Timer(val prefix: String) {
  private[this] val threadNanos = new ThreadLocal[LongRef] {
    override def initialValue() = {
      new LongRef(0)
    }
  }

  private val totalNanos = new AtomicLong

  def nanos: Long = totalNanos.get

  def start: TimerSnapshot = (threadNanos.get.elem, System.nanoTime())

  def stop(prev: TimerSnapshot): Unit = {
    val (nanos0, start) = prev
    val newThreadNanos = nanos0 + System.nanoTime() - start
    val threadNanosCount = threadNanos.get
    val diff = newThreadNanos - threadNanosCount.elem
    threadNanosCount.elem = newThreadNanos
    totalNanos.addAndGet(diff)
  }
}

object Timer {
  def apply(prefix: String): Timer = new Timer(prefix)
  type TimerSnapshot = (Long, Long)
}