package csw.location.server.internal

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import csw.location.server.commons.TestFutureExtension.RichFuture
import csw.location.server.internal.StreamExt.RichSource
import org.scalatest.{FunSuite, Matchers}

class StreamExtTest extends FunSuite with Matchers {
  private implicit val mat = ActorMaterializer()(ActorSystem())

  test("distinctUntilChanged") {
    Source(List(1, 2, 2, 3, 3, 3, 4, 1, 5, 5, 5, 4, 3, 4)).distinctUntilChanged.runWith(Sink.seq).await shouldBe List(
      1, 2, 3, 4, 1, 5, 4, 3, 4
    )
  }

}
