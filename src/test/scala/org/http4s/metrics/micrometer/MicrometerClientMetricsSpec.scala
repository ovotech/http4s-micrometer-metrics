package org.http4s.metrics.micrometer

import java.io.IOException
import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.implicits._
import cats.effect.{Timer => CatsEffectTimer, _}

import org.http4s._
import org.http4s.implicits._
import org.http4s.Uri.uri
import org.http4s.dsl.io._
import org.http4s.client._
import org.http4s.client.middleware.Metrics

import io.micrometer.core.instrument.{MeterRegistry, Tag}
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.core.instrument.search.MeterNotFoundException

import org.scalatest._

import util._

// TODO make the registry a resource as it needs to be closed afterward
class MicrometerClientMetricsSpec
    extends FlatSpec
    with Matchers
    with EitherValues {

  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val cf: ContextShift[IO] = IO.contextShift(ec)
  implicit val timer: CatsEffectTimer[IO] = IO.timer(ec)

  val client = Client.fromHttpApp[IO](HttpApp[IO](stub))

  behavior of "Http client with a micrometer metrics middleware"

  it should "register a 2xx response" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("client")
    val meteredClient = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(client)
    }.unsafeRunSync

    val resp = meteredClient.expect[String]("ok").attempt.unsafeRunSync()

    resp.right.value shouldBe "200 OK"

    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("client.default.3xx-responses"))
    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("client.default.4xx-responses"))
    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("client.default.5xx-responses"))

    meterCount(registry, Timer("client.default.2xx-responses")) shouldBe 1
    meterValue(registry, Gauge("client.default.active-requests")) shouldBe 0
    meterCount(registry, Timer("client.default.requests.total")) shouldBe 1

    meterMaxTime(registry, Timer("client.default.requests.total")) shouldBe 100.milliseconds
    meterMaxTime(registry, Timer("client.default.requests.headers")) shouldBe 50.milliseconds
    meterMaxTime(registry, Timer("client.default.2xx-responses")) shouldBe 100.milliseconds

    meterTotalTime(registry, Timer("client.default.requests.total")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("client.default.requests.headers")) shouldBe 50.milliseconds
    meterTotalTime(registry, Timer("client.default.2xx-responses")) shouldBe 100.milliseconds
  }

  it should "register a 4xx response" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("client")
    val meteredClient = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(client)
    }.unsafeRunSync

    val resp =
      meteredClient.expect[String]("bad-request").attempt.unsafeRunSync()

    resp.left.value shouldBe UnexpectedStatus(Status(400))

    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("client.default.2xx-responses"))
    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("client.default.3xx-responses"))
    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("client.default.5xx-responses"))

    meterCount(registry, Timer("client.default.4xx-responses")) shouldBe 1
    meterValue(registry, Gauge("client.default.active-requests")) shouldBe 0
    meterCount(registry, Timer("client.default.requests.total")) shouldBe 1

    meterMaxTime(registry, Timer("client.default.requests.total")) shouldBe 100.milliseconds
    meterMaxTime(registry, Timer("client.default.requests.headers")) shouldBe 50.milliseconds
    meterMaxTime(registry, Timer("client.default.4xx-responses")) shouldBe 100.milliseconds

    meterTotalTime(registry, Timer("client.default.requests.total")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("client.default.requests.headers")) shouldBe 50.milliseconds
    meterTotalTime(registry, Timer("client.default.4xx-responses")) shouldBe 100.milliseconds
  }

  it should "register a 5xx response" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("client")
    val meteredClient = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(client)
    }.unsafeRunSync

    val resp =
      meteredClient
        .expect[String]("internal-server-error")
        .attempt
        .unsafeRunSync()

    resp.left.value shouldBe UnexpectedStatus(Status(500))

    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("client.default.2xx-responses"))
    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("client.default.3xx-responses"))
    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("client.default.4xx-responses"))

    meterCount(registry, Timer("client.default.5xx-responses")) shouldBe 1
    meterValue(registry, Gauge("client.default.active-requests")) shouldBe 0
    meterCount(registry, Timer("client.default.requests.total")) shouldBe 1

    meterMaxTime(registry, Timer("client.default.requests.total")) shouldBe 100.milliseconds
    meterMaxTime(registry, Timer("client.default.requests.headers")) shouldBe 50.milliseconds
    meterMaxTime(registry, Timer("client.default.5xx-responses")) shouldBe 100.milliseconds

    meterTotalTime(registry, Timer("client.default.requests.total")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("client.default.requests.headers")) shouldBe 50.milliseconds
    meterTotalTime(registry, Timer("client.default.5xx-responses")) shouldBe 100.milliseconds
  }

  it should "register a GET request" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("client")
    val meteredClient = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(client)
    }.unsafeRunSync

    val resp = meteredClient.expect[String]("ok").attempt.unsafeRunSync()

    resp.right.value shouldBe "200 OK"

    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.post-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.put-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.patch-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.delete-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.head-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.move-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.options-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.trace-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.connect-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.other-requests"))

    meterCount(registry, Timer("client.default.get-requests")) shouldBe 1
    meterMaxTime(registry, Timer("client.default.get-requests")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("client.default.get-requests")) shouldBe 100.milliseconds
  }

  it should "register a POST request" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("client")
    val meteredClient = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(client)
    }.unsafeRunSync

    val resp = meteredClient
      .expect[String](Request[IO](POST, uri("ok")))
      .attempt
      .unsafeRunSync()

    resp.right.value shouldBe "200 OK"

    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.get-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.put-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.patch-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.delete-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.head-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.move-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.options-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.trace-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.connect-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.other-requests"))

    meterCount(registry, Timer("client.default.post-requests")) shouldBe 1
    meterMaxTime(registry, Timer("client.default.post-requests")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("client.default.post-requests")) shouldBe 100.milliseconds
  }

  it should "register a PUT request" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("client")
    val meteredClient = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(client)
    }.unsafeRunSync

    val resp = meteredClient
      .expect[String](Request[IO](PUT, uri("ok")))
      .attempt
      .unsafeRunSync()

    resp.right.value shouldBe "200 OK"

    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.get-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.post-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.patch-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.delete-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.head-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.move-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.options-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.trace-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.connect-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.other-requests"))

    meterCount(registry, Timer("client.default.put-requests")) shouldBe 1
    meterMaxTime(registry, Timer("client.default.put-requests")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("client.default.put-requests")) shouldBe 100.milliseconds
  }

  it should "register a PATCH request" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("client")
    val meteredClient = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(client)
    }.unsafeRunSync

    val resp = meteredClient
      .expect[String](Request[IO](PATCH, uri("ok")))
      .attempt
      .unsafeRunSync()

    resp.right.value shouldBe "200 OK"

    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.get-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.post-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.put-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.delete-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.head-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.move-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.options-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.trace-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.connect-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.other-requests"))

    meterCount(registry, Timer("client.default.patch-requests")) shouldBe 1
    meterMaxTime(registry, Timer("client.default.patch-requests")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("client.default.patch-requests")) shouldBe 100.milliseconds
  }

  it should "register a DELETE request" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("client")
    val meteredClient = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(client)
    }.unsafeRunSync

    val resp = meteredClient
      .expect[String](Request[IO](DELETE, uri("ok")))
      .attempt
      .unsafeRunSync()

    resp.right.value shouldBe "200 OK"

    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.get-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.post-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.put-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.patch-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.head-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.move-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.options-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.trace-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.connect-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.other-requests"))

    meterCount(registry, Timer("client.default.delete-requests")) shouldBe 1
    meterMaxTime(registry, Timer("client.default.delete-requests")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("client.default.delete-requests")) shouldBe 100.milliseconds
  }

  it should "register a HEAD request" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("client")
    val meteredClient = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(client)
    }.unsafeRunSync

    val resp = meteredClient
      .expect[String](Request[IO](HEAD, uri("ok")))
      .attempt
      .unsafeRunSync()

    resp.right.value shouldBe "200 OK"

    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.get-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.post-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.put-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.patch-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.delete-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.move-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.options-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.trace-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.connect-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.other-requests"))

    meterCount(registry, Timer("client.default.head-requests")) shouldBe 1
    meterMaxTime(registry, Timer("client.default.head-requests")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("client.default.head-requests")) shouldBe 100.milliseconds
  }

  it should "register a OPTIONS request" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("client")
    val meteredClient = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(client)
    }.unsafeRunSync

    val resp = meteredClient
      .expect[String](Request[IO](OPTIONS, uri("ok")))
      .attempt
      .unsafeRunSync()

    resp.right.value shouldBe "200 OK"

    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.get-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.post-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.put-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.patch-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.delete-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.move-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.head-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.trace-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.connect-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.other-requests"))

    meterCount(registry, Timer("client.default.options-requests")) shouldBe 1
    meterMaxTime(registry, Timer("client.default.options-requests")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("client.default.options-requests")) shouldBe 100.milliseconds
  }

  it should "register an error" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("client")
    val meteredClient = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(client)
    }.unsafeRunSync

    val resp = meteredClient
      .expect[String]("error")
      .attempt
      .unsafeRunSync()

    resp.left.value shouldBe an[IOException]

    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.abnormal-terminations"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.timeouts"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.get-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.requests.total"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.requests.headers"))

    meterCount(registry, Timer("client.default.errors")) shouldBe 1
    meterValue(registry, Gauge("client.default.active-requests")) shouldBe 0
  }

  it should "register a timeout" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("client")
    val meteredClient = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(client)
    }.unsafeRunSync

    val resp = meteredClient
      .expect[String]("timeout")
      .attempt
      .unsafeRunSync()

    resp.left.value shouldBe an[TimeoutException]

    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.abnormal-terminations"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.get-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.requests.total"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.requests.headers"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("client.default.errors"))

    meterCount(registry, Timer("client.default.timeouts")) shouldBe 1
    meterValue(registry, Gauge("client.default.active-requests")) shouldBe 0
  }

  it should "use the provided request classifier" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("client")
    val classifierFunc = (_: Request[IO]) => Some("classifier")
    val meteredClient = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](ops = micrometer, classifierF = classifierFunc)(client)
    }.unsafeRunSync

    val resp = meteredClient.expect[String]("ok").attempt.unsafeRunSync()

    resp.right.value shouldBe "200 OK"

    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("client.default.get-requests"))
    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("client.default.2xx-responses"))
    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("client.default.3xx-responses"))
    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("client.default.4xx-responses"))
    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("client.default.5xx-responses"))

    meterCount(registry, Timer("client.classifier.get-requests")) shouldBe 1
    meterMaxTime(registry, Timer("client.classifier.get-requests")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("client.classifier.get-requests")) shouldBe 100.milliseconds
  }

  it should "only record total time and decr active requests after client.run releases" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("client")
    val meteredClient = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(client)
    }.unsafeRunSync

    val clientRunResource = meteredClient
      .run(Request[IO](uri = Uri.unsafeFromString("ok")))
      .use { resp =>
        IO {
          EntityDecoder[IO, String]
            .decode(resp, false)
            .value
            .unsafeRunSync()
            .right
            .value shouldBe "200 OK"
          meterValue(registry, Gauge("client.default.active-requests")) shouldBe 1
          meterMaxTime(registry, Timer("client.default.requests.headers")) shouldBe 50.milliseconds

          a[MeterNotFoundException] should be thrownBy meterCount(
            registry,
            Timer("client.default.2xx-responses")
          )

          a[MeterNotFoundException] should be thrownBy meterCount(
            registry,
            Timer("client.default.requests.total")
          )
        }
      }
      .unsafeRunSync()

    meterCount(registry, Timer("client.default.2xx-responses")) shouldBe 1
    meterValue(registry, Gauge("client.default.active-requests")) shouldBe 0
    meterCount(registry, Timer("client.default.requests.total")) shouldBe 1

    meterMaxTime(registry, Timer("client.default.2xx-responses")) shouldBe 100.milliseconds
    meterMaxTime(registry, Timer("client.default.requests.total")) shouldBe 100.milliseconds
    meterMaxTime(registry, Timer("client.default.requests.headers")) shouldBe 50.milliseconds
  }
}
