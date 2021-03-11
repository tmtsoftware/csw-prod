package csw.event.client.perf.ocs.gateway.client

import akka.NotUsed
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.actor.typed.{ActorSystem, Scheduler, SpawnProtocol}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, Uri}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.unmarshalling.sse.EventStreamUnmarshalling._
import akka.stream.scaladsl.{Keep, Source}
import akka.stream.{KillSwitches, UniqueKillSwitch}
import csw.location.api.scaladsl.LocationService
import csw.location.client.scaladsl.HttpLocationServiceFactory
import csw.params.core.formats.JsonSupport
import csw.params.events.{Event, EventKey}
import csw.prefix.models.Prefix
import esw.gateway.api.clients.EventClient
import esw.gateway.api.codecs.GatewayCodecs._
import esw.gateway.api.protocol.{GatewayRequest, GatewayStreamRequest}
import esw.ocs.testkit.utils.KeycloakUtils
import msocket.api.ContentType
import msocket.http.post.HttpPostTransport
import msocket.http.ws.WebsocketTransport
import play.api.libs.json.Json

import scala.async.Async._
import scala.concurrent.Future

class GatewayClient(serverIp: String, port: Int, subId : Int)(implicit val actorSystem: ActorSystem[SpawnProtocol.Command])
    extends KeycloakUtils {

  import actorSystem.executionContext
  implicit val scheduler: Scheduler = actorSystem.scheduler
  private val baseUri               = s"http://$serverIp:$port/events"

  lazy val token: () => Option[String] = getToken("esw-user", "esw-user")
  lazy val eventClient: EventClient    = new EventClient(gatewayHTTPClient(token), gatewayWebSocketClient(Prefix("esw.EswGateway")))

  override def locationService: LocationService = HttpLocationServiceFactory.makeLocalClient
  override lazy val keycloakPort: Int           = 8081

  def subscribe(keys: Set[EventKey]): Source[Event, UniqueKillSwitch] = {
    // keys.map(subscribe).reduce(_ merge _)
    eventClient.subscribe(keys, None).viaMat(KillSwitches.single)(Keep.right)
  }

  private def gatewayHTTPClient(tokenFactory: () => Option[String] = () => None) = {
    val httpUri = Uri(s"http://$serverIp:$port").withPath(Path("/post-endpoint")).toString()
    new HttpPostTransport[GatewayRequest](httpUri, ContentType.Json, tokenFactory)
  }

  private def gatewayWebSocketClient(prefix: Prefix) = {
    val webSocketUri = Uri(s"http://$serverIp:$port").withScheme("ws").withPath(Path("/websocket-endpoint")).toString()
    val appName = Some(s"Subscriber-$subId")
    new WebsocketTransport[GatewayStreamRequest](webSocketUri, ContentType.Json, token, appName)
  }

  def subscribe(eventKey: EventKey): Source[Event, UniqueKillSwitch] = {
    val subsystem = eventKey.source.subsystem
    val prefix    = eventKey.source
    val component = prefix.componentName

    val uri     = Uri(s"$baseUri/subscribe/$subsystem?component=$component&event=${eventKey.eventName.name}")
    val request = HttpRequest(HttpMethods.GET, uri = uri)

    val sseStreamFuture: Future[Source[ServerSentEvent, NotUsed]] = async {
      val response              = await(Http().singleRequest(request))
      implicit val unmarshaller = fromEventsStream(actorSystem.toClassic)
      await(Unmarshal(response.entity).to[Source[ServerSentEvent, NotUsed]])
    }

    val sseStream = Source.future(sseStreamFuture).flatMapConcat(identity)
    sseStream.map(x => JsonSupport.reads[Event](Json.parse(x.data))).viaMat(KillSwitches.single)(Keep.right)
  }

}

//object Main extends App {
//
//  private implicit val system: typed.ActorSystem[_] = ActorSystemFactory.remote(Behaviors.empty, "event-client-system")
//
//  private val client = new GatewayClient("localhost", 9090)
//
//  private val eventName = EventName("gateway")
//  private val prefix    = Prefix("tcs.test")
//
//  (1 to 50).map { _ =>
//    client
//      .subscribe(EventKey(prefix, eventName))
//      .runForeach(println)
//  }
//
//  private val factory                    = new EventServiceFactory()
//  private val eventService: EventService = factory.make("localhost", 26379)
//
//  Thread.sleep(1000)
//  eventService.defaultPublisher.publish(
//    EventUtils.event(eventName, prefix, 2)
//  )
//}
