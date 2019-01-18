package org.http4s.metrics.micrometer

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.implicits._
import cats.effect.{Timer => CatsEffectTimer, _}

import org.http4s._
import org.http4s.implicits._
import org.http4s.Uri.uri
import org.http4s.dsl.io._
import org.http4s.server.middleware.Metrics

import io.micrometer.core.instrument.{MeterRegistry, Tag}
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.core.instrument.search.MeterNotFoundException

import org.scalatest._

import util._

// TODO make the registry a resource as it needs to be closed afterward
class MicrometerServerMetricsSpec extends FlatSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val cf: ContextShift[IO] = IO.contextShift(ec)
  implicit val timer: CatsEffectTimer[IO] = IO.timer(ec)

  val stubRoutes = HttpRoutes.of[IO](stub)

  behavior of "Http routes with a micrometer metrics middleware"

  it should "register a 2xx response" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("server")
    val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(stubRoutes)
    }.unsafeRunSync

    val req = Request[IO](uri = uri("/ok"))
    val resp: Response[IO] = meteredRoutes.orNotFound(req).unsafeRunSync

    resp.status shouldBe Status.Ok
    resp.as[String].unsafeRunSync shouldBe "200 OK"

    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("server.default.3xx-responses"))
    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("server.default.4xx-responses"))
    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("server.default.5xx-responses"))

    meterCount(registry, Timer("server.default.2xx-responses")) shouldBe 1
    meterValue(registry, Gauge("server.default.active-requests")) shouldBe 0
    meterCount(registry, Timer("server.default.requests.total")) shouldBe 1

    meterMaxTime(registry, Timer("server.default.requests.total")) shouldBe 100.milliseconds
    meterMaxTime(registry, Timer("server.default.requests.headers")) shouldBe 50.milliseconds
    meterMaxTime(registry, Timer("server.default.2xx-responses")) shouldBe 100.milliseconds

    meterTotalTime(registry, Timer("server.default.requests.total")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("server.default.requests.headers")) shouldBe 50.milliseconds
    meterTotalTime(registry, Timer("server.default.2xx-responses")) shouldBe 100.milliseconds
  }

  it should "register a 4xx response" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("server")
    val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(stubRoutes)
    }.unsafeRunSync

    val req = Request[IO](uri = uri("/bad-request"))

    val resp = meteredRoutes.orNotFound(req).unsafeRunSync

    resp.status shouldBe Status.BadRequest
    resp.as[String].unsafeRunSync shouldBe "400 Bad Request"

    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("server.default.2xx-responses"))
    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("server.default.3xx-responses"))
    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("server.default.5xx-responses"))

    meterCount(registry, Timer("server.default.4xx-responses")) shouldBe 1
    meterValue(registry, Gauge("server.default.active-requests")) shouldBe 0
    meterCount(registry, Timer("server.default.requests.total")) shouldBe 1

    meterMaxTime(registry, Timer("server.default.requests.total")) shouldBe 100.milliseconds
    meterMaxTime(registry, Timer("server.default.requests.headers")) shouldBe 50.milliseconds
    meterMaxTime(registry, Timer("server.default.4xx-responses")) shouldBe 100.milliseconds

    meterTotalTime(registry, Timer("server.default.requests.total")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("server.default.requests.headers")) shouldBe 50.milliseconds
    meterTotalTime(registry, Timer("server.default.4xx-responses")) shouldBe 100.milliseconds
  }

  it should "register a 5xx response" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("server")
    val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(stubRoutes)
    }.unsafeRunSync

    val req = Request[IO](uri = uri("/internal-server-error"))

    val resp = meteredRoutes.orNotFound(req).unsafeRunSync

    resp.status shouldBe Status.InternalServerError
    resp.as[String].unsafeRunSync shouldBe "500 Internal Server Error"

    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("server.default.2xx-responses"))
    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("server.default.3xx-responses"))
    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("server.default.4xx-responses"))

    meterCount(registry, Timer("server.default.5xx-responses")) shouldBe 1
    meterValue(registry, Gauge("server.default.active-requests")) shouldBe 0
    meterCount(registry, Timer("server.default.requests.total")) shouldBe 1

    meterMaxTime(registry, Timer("server.default.requests.total")) shouldBe 100.milliseconds
    meterMaxTime(registry, Timer("server.default.requests.headers")) shouldBe 50.milliseconds
    meterMaxTime(registry, Timer("server.default.5xx-responses")) shouldBe 100.milliseconds

    meterTotalTime(registry, Timer("server.default.requests.total")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("server.default.requests.headers")) shouldBe 50.milliseconds
    meterTotalTime(registry, Timer("server.default.5xx-responses")) shouldBe 100.milliseconds
  }

  it should "register a GET request" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("server")
    val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(stubRoutes)
    }.unsafeRunSync

    val req = Request[IO](method = GET, uri = uri("/ok"))

    val resp = meteredRoutes.orNotFound(req).unsafeRunSync

    resp.status shouldBe Status.Ok
    resp.as[String].unsafeRunSync shouldBe "200 OK"

    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.post-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.put-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.patch-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.delete-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.head-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.move-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.options-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.trace-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.connect-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.other-requests"))

    meterCount(registry, Timer("server.default.get-requests")) shouldBe 1
    meterMaxTime(registry, Timer("server.default.get-requests")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("server.default.get-requests")) shouldBe 100.milliseconds
  }

  it should "register a POST request" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("server")
    val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(stubRoutes)
    }.unsafeRunSync

    val req = Request[IO](method = POST, uri = uri("/ok"))

    val resp = meteredRoutes.orNotFound(req).unsafeRunSync

    resp.status shouldBe Status.Ok
    resp.as[String].unsafeRunSync shouldBe "200 OK"

    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.get-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.put-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.patch-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.delete-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.head-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.move-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.options-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.trace-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.connect-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.other-requests"))

    meterCount(registry, Timer("server.default.post-requests")) shouldBe 1
    meterMaxTime(registry, Timer("server.default.post-requests")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("server.default.post-requests")) shouldBe 100.milliseconds
  }

  it should "register a PUT request" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("server")
    val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(stubRoutes)
    }.unsafeRunSync

    val req = Request[IO](method = PUT, uri = uri("/ok"))

    val resp = meteredRoutes.orNotFound(req).unsafeRunSync

    resp.status shouldBe Status.Ok
    resp.as[String].unsafeRunSync shouldBe "200 OK"

    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.get-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.post-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.patch-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.delete-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.head-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.move-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.options-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.trace-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.connect-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.other-requests"))

    meterCount(registry, Timer("server.default.put-requests")) shouldBe 1
    meterMaxTime(registry, Timer("server.default.put-requests")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("server.default.put-requests")) shouldBe 100.milliseconds
  }

  it should "register a PATCH request" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("server")
    val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(stubRoutes)
    }.unsafeRunSync

    val req = Request[IO](method = PATCH, uri = uri("/ok"))

    val resp = meteredRoutes.orNotFound(req).unsafeRunSync

    resp.status shouldBe Status.Ok
    resp.as[String].unsafeRunSync shouldBe "200 OK"

    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.get-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.post-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.put-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.delete-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.head-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.move-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.options-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.trace-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.connect-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.other-requests"))

    meterCount(registry, Timer("server.default.patch-requests")) shouldBe 1
    meterMaxTime(registry, Timer("server.default.patch-requests")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("server.default.patch-requests")) shouldBe 100.milliseconds
  }

  it should "register a DELETE request" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("server")
    val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(stubRoutes)
    }.unsafeRunSync

    val req = Request[IO](method = DELETE, uri = uri("/ok"))

    val resp = meteredRoutes.orNotFound(req).unsafeRunSync

    resp.status shouldBe Status.Ok
    resp.as[String].unsafeRunSync shouldBe "200 OK"

    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.get-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.post-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.put-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.patch-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.head-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.move-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.options-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.trace-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.connect-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.other-requests"))

    meterCount(registry, Timer("server.default.delete-requests")) shouldBe 1
    meterMaxTime(registry, Timer("server.default.delete-requests")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("server.default.delete-requests")) shouldBe 100.milliseconds
  }

  it should "register a HEAD request" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("server")
    val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(stubRoutes)
    }.unsafeRunSync

    val req = Request[IO](method = HEAD, uri = uri("/ok"))

    val resp = meteredRoutes.orNotFound(req).unsafeRunSync

    resp.status shouldBe Status.Ok
    resp.as[String].unsafeRunSync shouldBe "200 OK"

    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.get-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.post-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.put-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.patch-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.delete-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.move-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.options-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.trace-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.connect-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.other-requests"))

    meterCount(registry, Timer("server.default.head-requests")) shouldBe 1
    meterMaxTime(registry, Timer("server.default.head-requests")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("server.default.head-requests")) shouldBe 100.milliseconds
  }

  it should "register a OPTIONS request" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("server")
    val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(stubRoutes)
    }.unsafeRunSync

    val req = Request[IO](method = OPTIONS, uri = uri("/ok"))

    val resp = meteredRoutes.orNotFound(req).unsafeRunSync

    resp.status shouldBe Status.Ok
    resp.as[String].unsafeRunSync shouldBe "200 OK"

    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.get-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.post-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.put-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.patch-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.delete-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.move-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.head-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.trace-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.connect-requests"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.other-requests"))

    meterCount(registry, Timer("server.default.options-requests")) shouldBe 1
    meterMaxTime(registry, Timer("server.default.options-requests")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("server.default.options-requests")) shouldBe 100.milliseconds
  }

  it should "register an error" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("server")
    val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(stubRoutes)
    }.unsafeRunSync

    val req = Request[IO](method = GET, uri = uri("/error"))

    val resp = meteredRoutes.orNotFound(req).attempt.unsafeRunSync

    resp shouldBe a[Left[_, _]]

    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.abnormal-terminations"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.timeouts"))

    meterCount(registry, Timer("server.default.errors")) shouldBe 1
    meterCount(registry, Timer("server.default.get-requests")) shouldBe 1
    meterCount(registry, Timer("server.default.requests.total")) shouldBe 1
    meterCount(registry, Timer("server.default.requests.headers")) shouldBe 1
  }

  it should "register an abnormal termination" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("server")
    val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](micrometer)(stubRoutes)
    }.unsafeRunSync

    val req = Request[IO](method = GET, uri = uri("/abnormal-termination"))

    val resp = meteredRoutes.orNotFound(req).unsafeRunSync

    resp.status shouldBe Status.Ok
    resp.body.attempt.compile.lastOrError.unsafeRunSync shouldBe a[Left[_, _]]

    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.errors"))
    a[MeterNotFoundException] should be thrownBy
      meterCount(registry, Timer("server.default.timeouts"))

    meterCount(registry, Timer("server.default.abnormal-terminations")) shouldBe 1
    meterCount(registry, Timer("server.default.get-requests")) shouldBe 1
    meterCount(registry, Timer("server.default.requests.total")) shouldBe 1
    meterCount(registry, Timer("server.default.requests.headers")) shouldBe 1
  }

  // TODO how to simulate a timeout???

  it should "use the provided request classifier" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config = Config("server")
    val classifierFunc = (_: Request[IO]) => Some("classifier")
    val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](ops = micrometer, classifierF = classifierFunc)(stubRoutes)
    }.unsafeRunSync

    val req = Request[IO](uri = uri("/ok"))
    val resp: Response[IO] = meteredRoutes.orNotFound(req).unsafeRunSync

    resp.status shouldBe Status.Ok
    resp.as[String].unsafeRunSync shouldBe "200 OK"

    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("server.default.get-requests"))
    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("server.default.2xx-responses"))
    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("server.default.3xx-responses"))
    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("server.default.4xx-responses"))
    a[MeterNotFoundException] should be thrownBy meterCount(
      registry,
      Timer("server.default.5xx-responses"))

    meterCount(registry, Timer("server.classifier.get-requests")) shouldBe 1
    meterMaxTime(registry, Timer("server.classifier.get-requests")) shouldBe 100.milliseconds
    meterTotalTime(registry, Timer("server.classifier.get-requests")) shouldBe 100.milliseconds
  }

}
