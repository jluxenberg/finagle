package com.twitter.finagle.channel

import com.twitter.collection.BucketGenerationalQueue
import com.twitter.finagle.service.FailedService
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}

import com.twitter.util.Duration
import java.util.concurrent.atomic.AtomicInteger
import com.twitter.finagle.{ConnectionRefusedException, SimpleFilter, Service, ClientConnection}

case class OpenConnectionsThresholds(
  lowWaterMark: Int,
  highWaterMark: Int,
  idleTimeout: Duration
) {
  require(lowWaterMark <= highWaterMark, "lowWaterMark must be <= highWaterMark")
}

/**
 * Filter responsible for tracking idle connection, it will refuse requests and try to close idle
 * connections based on the number of active connections.
 *
  * Each time a message from a new connection arrive (based on nb of connections):
 * - if below low watermark: accept the connection.
 * - if above low watermark: collect (close) idle connections, but accept the connection.
 * - if above high watermark: collect (close) idle connections, and refuse/accept the
 *   connection depending if we managed to close an idle connection.
 *
 * NB: the connection is tracked after the server response, so that the server processing time is
 * not count in the idle timeout.
 */
class IdleConnectionFilter[Req, Rep](
  threshold: OpenConnectionsThresholds,
  underlying: ClientConnection => Service[Req, Rep],
  statsReceiver: StatsReceiver = NullStatsReceiver
) extends (ClientConnection => Service[Req, Rep]) {

  private[this] val queue = new BucketGenerationalQueue[ClientConnection](threshold.idleTimeout)
  private[this] val connectionCounter = new AtomicInteger(0)
  private[this] val idle = statsReceiver.addGauge("idle") {
    queue.collectAll(threshold.idleTimeout).size
  }
  private[this] val refused = statsReceiver.counter("refused")

  def openConnections = connectionCounter.get()

  def apply(c: ClientConnection) = {
    val service = if (accept(c)) {
      filterFactory(c) andThen underlying(c)
    } else {
      refused.incr()
      val address = c.remoteAddress
      c.close()
      new FailedService[Req, Rep](new ConnectionRefusedException(address))
    }
    c.onClose ensure { connectionCounter.decrementAndGet() }
    service
  }

  // This filter is responsible for adding/removing a connection to/from the idle tracking
  // system during the phase when the server is computing the result.
  // So if a request take a long time to be processed, we will never detect it as idle
  // NB: private[channel] for testing purpose only
  private[channel] def filterFactory(c: ClientConnection) = new SimpleFilter[Req, Rep] {
    def apply(request: Req, service: Service[Req, Rep]) = {
      queue.remove(c)
      service(request) ensure {
        queue.touch(c)
      }
    }
  }

  private[channel] def closeIdleConnections() =
    queue.collect(threshold.idleTimeout) match {
      case Some(conn) =>
        conn.close(); true
      case None =>
        false
    }

  private[this] def accept(c: ClientConnection): Boolean = {
    val connectionCount = connectionCounter.incrementAndGet()
    if (connectionCount <= threshold.lowWaterMark)
      true
    else if (connectionCount <= threshold.highWaterMark) {
      closeIdleConnections() // whatever the result of this, we accept the connection
      true
    } else {
      // Try to close idle connections, if we don't find any, then we refuse the connection
      closeIdleConnections()
    }
  }
}
