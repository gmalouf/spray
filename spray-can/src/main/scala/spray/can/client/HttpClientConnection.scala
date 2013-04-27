/*
 * Copyright (C) 2011-2013 spray.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package spray.can
package client

import scala.concurrent.duration.Duration
import akka.actor.{ ReceiveTimeout, ActorRef }
import akka.io.{ Tcp, IO }
import spray.http.{ Confirmed, HttpRequestPart }
import spray.io._

private[can] class HttpClientConnection(connectCommander: ActorRef,
                                        connect: Http.Connect,
                                        pipelineStage: RawPipelineStage[SslTlsContext],
                                        settings: ClientConnectionSettings) extends ConnectionHandler { actor ⇒
  import context.system
  import connect._

  log.debug("Attempting connection to {}", remoteAddress)

  IO(Tcp) ! Tcp.Connect(remoteAddress, localAddress, options)

  context.setReceiveTimeout(settings.connectingTimeout)

  def receive: Receive = {
    case connected: Tcp.Connected ⇒
      context.setReceiveTimeout(Duration.Undefined)
      log.debug("Connected to {}", connected.remoteAddress)
      val tcpConnection = sender
      tcpConnection ! Tcp.Register(self)
      context.watch(tcpConnection)
      connectCommander ! connected
      context.become(running(tcpConnection, pipelineStage, pipelineContext(connected)))

    case Tcp.CommandFailed(_: Tcp.Connect) ⇒
      connectCommander ! Http.CommandFailed(connect)
      context.stop(self)

    case ReceiveTimeout ⇒
      log.warning("Configured connecting timeout of {} expired, stopping", settings.connectingTimeout)
      connectCommander ! Http.CommandFailed(connect)
      context.stop(self)
  }

  override def running(tcpConnection: ActorRef, pipelines: Pipelines): Receive =
    super.running(tcpConnection, pipelines) orElse {
      case x: HttpRequestPart                   ⇒ pipelines.commandPipeline(Http.MessageCommand(x))
      case x @ Confirmed(_: HttpRequestPart, _) ⇒ pipelines.commandPipeline(Http.MessageCommand(x))
    }

  def pipelineContext(connected: Tcp.Connected) = new SslTlsContext {
    def actorContext = context
    def remoteAddress = connected.remoteAddress
    def localAddress = connected.localAddress
    def log = actor.log
    def sslEngine = sslEngineProvider(this)
  }
}

private[can] object HttpClientConnection {

  def pipelineStage(settings: ClientConnectionSettings) = {
    import settings._
    ClientFrontend(requestTimeout) >>
      ResponseChunkAggregation(responseChunkAggregationLimit) ? (responseChunkAggregationLimit > 0) >>
      ResponseParsing(parserSettings) >>
      RequestRendering(settings) >>
      ConnectionTimeouts(idleTimeout) ? (reapingCycle.isFinite && idleTimeout.isFinite) >>
      SslTlsSupport ? sslEncryption >>
      TickGenerator(reapingCycle) ? (idleTimeout.isFinite || requestTimeout.isFinite)
  }

}

