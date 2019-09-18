package org.http4s.metrics.micrometer

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.effect.{Timer => CatsEffectTimer, _}

import org.http4s._
import org.http4s.implicits._
import org.http4s.Uri.uri
import org.http4s.dsl.io._
import org.http4s.server.middleware.Metrics

import io.micrometer.core.instrument.{MeterRegistry, Tags}
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.core.instrument.search.MeterNotFoundException

import org.scalatest._

import util._

class MicrometerServerMetricsSpec extends FlatSpec with Matchers {

  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val cf: ContextShift[IO] = IO.contextShift(ec)
  implicit val timer: CatsEffectTimer[IO] = IO.timer(ec)

  val stubRoutes = HttpRoutes.of[IO](stub)

  def testMetersFor(
      registry: MeterRegistry,
      method: String = "get",
      statusCode: String = "2xx",
      classifier: String = "default",
      termination: String = "normal",
      additionalTags: Tags = Tags.empty
  ) = {

    // TODO test for non existence of classifier

    val allStatuses = List(
      "2xx",
      "3xx",
      "4xx",
      "5xx"
    )

    val allMethods = List(
      "get",
      "put",
      "post",
      "patch",
      "delete",
      "head",
      "move",
      "options",
      "trace",
      "connect",
      "other"
    )

    val allTerminations = List(
      "abnormal",
      "error",
      "timeout"
    )

    allStatuses.filter(_ != statusCode).foreach { x =>
      a[MeterNotFoundException] should be thrownBy meterCount(
        registry,
        Timer(s"server.${classifier}.response-time", additionalTags and Tags.of("status-code", x))
      )
    }

    allMethods.filter(_ != method).foreach { x =>
      a[MeterNotFoundException] should be thrownBy meterCount(
        registry,
        Timer(s"server.${classifier}.response-time", additionalTags and Tags.of("method", x))
      )

      a[MeterNotFoundException] should be thrownBy meterCount(
        registry,
        Timer(
          s"server.${classifier}.response-headers-time",
          additionalTags and Tags.of("method", x)
        )
      )
    }

    allTerminations.filter(_ != termination).foreach { x =>
      a[MeterNotFoundException] should be thrownBy meterCount(
        registry,
        Timer(s"server.${classifier}.response-time", additionalTags and Tags.of("termination", x))
      )
    }

    val responseTimeTags = if (termination != "normal") {
      Tags.of("termination", termination)
    } else {
      Tags.of("status-code", statusCode, "method", method, "termination", termination)
    }

    meterCount(
      registry,
      Timer(
        s"server.${classifier}.response-time",
        additionalTags and responseTimeTags
      )
    ) shouldBe 1

    if (termination == "normal") {
      meterMaxTime(
        registry,
        Timer(
          s"server.${classifier}.response-time",
          additionalTags and responseTimeTags
        )
      ) shouldBe 100.milliseconds

      meterTotalTime(
        registry,
        Timer(
          s"server.${classifier}.response-time",
          additionalTags and responseTimeTags
        )
      ) shouldBe 100.milliseconds
    }

    meterCount(
      registry,
      Timer(
        s"server.${classifier}.response-headers-time",
        additionalTags and Tags.of("method", method)
      )
    ) shouldBe 1

    meterMaxTime(
      registry,
      Timer(
        s"server.${classifier}.response-headers-time",
        additionalTags and Tags.of("method", method)
      )
    ) shouldBe 50.milliseconds

    meterTotalTime(
      registry,
      Timer(
        s"server.${classifier}.response-headers-time",
        additionalTags and Tags.of("method", method)
      )
    ) shouldBe 50.milliseconds

    meterValue(
      registry,
      Gauge(s"server.${classifier}.active-requests", additionalTags)
    ) shouldBe 0
  }

  behavior of "Http routes with a micrometer metrics middleware"

  it should "register a 2xx response" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("server.")
    meterRegistryResource.use { registry =>
      IO {
        val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
          Metrics[IO](micrometer)(stubRoutes)
        }.unsafeRunSync

        val req = Request[IO](uri = uri("/ok"))
        val resp: Response[IO] = meteredRoutes.orNotFound(req).unsafeRunSync

        resp.status shouldBe Status.Ok
        resp.as[String].unsafeRunSync shouldBe "200 OK"

        testMetersFor(registry, "get", "2xx")
      }
    }.unsafeRunSync
  }

  it should "register a 4xx response" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("server.")
    meterRegistryResource.use { registry =>
      IO {

        val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
          Metrics[IO](micrometer)(stubRoutes)
        }.unsafeRunSync

        val req = Request[IO](uri = uri("/bad-request"))

        val resp = meteredRoutes.orNotFound(req).unsafeRunSync

        resp.status shouldBe Status.BadRequest
        resp.as[String].unsafeRunSync shouldBe "400 Bad Request"

        testMetersFor(registry, "get", "4xx")
      }
    }.unsafeRunSync
  }

  it should "register a 5xx response" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("server.")
    meterRegistryResource.use { registry =>
      IO {

        val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
          Metrics[IO](micrometer)(stubRoutes)
        }.unsafeRunSync

        val req = Request[IO](uri = uri("/internal-server-error"))

        val resp = meteredRoutes.orNotFound(req).unsafeRunSync

        resp.status shouldBe Status.InternalServerError
        resp.as[String].unsafeRunSync shouldBe "500 Internal Server Error"

        testMetersFor(registry, "get", "5xx")
      }
    }.unsafeRunSync
  }

  it should "register a POST request" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("server.")
    meterRegistryResource.use { registry =>
      IO {

        val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
          Metrics[IO](micrometer)(stubRoutes)
        }.unsafeRunSync

        val req = Request[IO](method = POST, uri = uri("/ok"))

        val resp = meteredRoutes.orNotFound(req).unsafeRunSync

        resp.status shouldBe Status.Ok
        resp.as[String].unsafeRunSync shouldBe "200 OK"

        testMetersFor(registry, "post")
      }
    }.unsafeRunSync
  }

  it should "register a PUT request" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("server.")
    meterRegistryResource.use { registry =>
      IO {
        val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
          Metrics[IO](micrometer)(stubRoutes)
        }.unsafeRunSync

        val req = Request[IO](method = PUT, uri = uri("/ok"))

        val resp = meteredRoutes.orNotFound(req).unsafeRunSync

        resp.status shouldBe Status.Ok
        resp.as[String].unsafeRunSync shouldBe "200 OK"

        testMetersFor(registry, "put")
      }
    }.unsafeRunSync
  }

  it should "register a PATCH request" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("server.")
    meterRegistryResource.use { registry =>
      IO {
        val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
          Metrics[IO](micrometer)(stubRoutes)
        }.unsafeRunSync

        val req = Request[IO](method = PATCH, uri = uri("/ok"))

        val resp = meteredRoutes.orNotFound(req).unsafeRunSync

        resp.status shouldBe Status.Ok
        resp.as[String].unsafeRunSync shouldBe "200 OK"

        testMetersFor(registry, "patch")
      }
    }.unsafeRunSync
  }

  it should "register a DELETE request" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("server.")
    meterRegistryResource.use { registry =>
      IO {
        val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
          Metrics[IO](micrometer)(stubRoutes)
        }.unsafeRunSync

        val req = Request[IO](method = DELETE, uri = uri("/ok"))

        val resp = meteredRoutes.orNotFound(req).unsafeRunSync

        resp.status shouldBe Status.Ok
        resp.as[String].unsafeRunSync shouldBe "200 OK"

        testMetersFor(registry, "delete")
      }
    }.unsafeRunSync
  }

  it should "register a HEAD request" in {
    implicit val clock = FakeClock[IO]

    val config: Config = Config("server.")
    meterRegistryResource.use { registry =>
      IO {
        val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
          Metrics[IO](micrometer)(stubRoutes)
        }.unsafeRunSync

        val req = Request[IO](method = HEAD, uri = uri("/ok"))

        val resp = meteredRoutes.orNotFound(req).unsafeRunSync

        resp.status shouldBe Status.Ok
        resp.as[String].unsafeRunSync shouldBe "200 OK"

        testMetersFor(registry, "head")
      }
    }.unsafeRunSync
  }

  it should "register a OPTIONS request" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("server.")
    meterRegistryResource.use { registry =>
      IO {
        val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
          Metrics[IO](micrometer)(stubRoutes)
        }.unsafeRunSync

        val req = Request[IO](method = OPTIONS, uri = uri("/ok"))

        val resp = meteredRoutes.orNotFound(req).unsafeRunSync

        resp.status shouldBe Status.Ok
        resp.as[String].unsafeRunSync shouldBe "200 OK"

        testMetersFor(registry, "options")
      }
    }.unsafeRunSync
  }

  it should "register an error" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("server.")
    meterRegistryResource.use { registry =>
      IO {

        val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
          Metrics[IO](micrometer)(stubRoutes)
        }.unsafeRunSync

        val req = Request[IO](method = GET, uri = uri("/error"))

        val resp = meteredRoutes.orNotFound(req).attempt.unsafeRunSync

        resp shouldBe a[Left[_, _]]

        testMetersFor(registry, statusCode = "5xx", termination = "error")
      }
    }.unsafeRunSync
  }

  it should "register an abnormal termination" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("server.")
    meterRegistryResource.use { registry =>
      IO {

        val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
          Metrics[IO](micrometer)(stubRoutes)
        }.unsafeRunSync

        val req = Request[IO](method = GET, uri = uri("/abnormal-termination"))

        val resp = meteredRoutes.orNotFound(req).unsafeRunSync

        resp.status shouldBe Status.Ok
        resp.body.attempt.compile.lastOrError.unsafeRunSync shouldBe a[Left[_, _]]

        testMetersFor(registry, termination = "abnormal")
      }
    }.unsafeRunSync
  }

  // TODO how to simulate a timeout???

  it should "use the provided request classifier" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("server.")
    val classifierFunc = (_: Request[IO]) => Some("classifier")
    meterRegistryResource.use { registry =>
      IO {
        val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
          Metrics[IO](ops = micrometer, classifierF = classifierFunc)(stubRoutes)
        }.unsafeRunSync

        val req = Request[IO](uri = uri("/ok"))
        val resp: Response[IO] = meteredRoutes.orNotFound(req).unsafeRunSync

        resp.status shouldBe Status.Ok
        resp.as[String].unsafeRunSync shouldBe "200 OK"

        testMetersFor(registry, classifier = "classifier")
      }
    }.unsafeRunSync
  }

  it should "tags metrics using global tags" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("server.", tags = Tags.of("foo", "bar"))
    meterRegistryResource.use { registry =>
      IO {
        val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
          Metrics[IO](micrometer)(stubRoutes)
        }.unsafeRunSync

        val req = Request[IO](uri = uri("/ok"))
        val resp: Response[IO] = meteredRoutes.orNotFound(req).unsafeRunSync

        resp.status shouldBe Status.Ok
        resp.as[String].unsafeRunSync shouldBe "200 OK"

        testMetersFor(registry, additionalTags = Tags.of("foo", "bar"))
      }
    }.unsafeRunSync
  }

  it should "use the provided request classifier to overwrite the tags" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config =
      Config("server.", tags = Tags.of("foo", "bar", "bar", "baz"))
    val classifierFunc =
      (_: Request[IO]) => Some("classifier[bar:bazv2,baz:bar]")
    val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](ops = micrometer, classifierF = classifierFunc)(stubRoutes)
    }.unsafeRunSync

    val req = Request[IO](uri = uri("/ok"))
    val resp: Response[IO] = meteredRoutes.orNotFound(req).unsafeRunSync

    resp.status shouldBe Status.Ok
    resp.as[String].unsafeRunSync shouldBe "200 OK"

    testMetersFor(
      registry,
      classifier = "classifier",
      additionalTags = Tags.of("foo", "bar", "bar", "bazv2", "baz", "bar")
    )
  }

  it should "use the provided request  empty classifier to overwrite the tags" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config =
      Config("server.", tags = Tags.of("foo", "bar", "bar", "baz"))
    val classifierFunc =
      (_: Request[IO]) => Some("[bar:bazv2,baz:bar]")
    val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](ops = micrometer, classifierF = classifierFunc)(stubRoutes)
    }.unsafeRunSync

    val req = Request[IO](uri = uri("/ok"))
    val resp: Response[IO] = meteredRoutes.orNotFound(req).unsafeRunSync

    resp.status shouldBe Status.Ok
    resp.as[String].unsafeRunSync shouldBe "200 OK"

    testMetersFor(
      registry,
      additionalTags = Tags.of("foo", "bar", "bar", "bazv2", "baz", "bar")
    )
  }

  it should "handle classifier with empty tags" in {
    implicit val clock = FakeClock[IO]
    val registry: MeterRegistry = new SimpleMeterRegistry
    val config: Config =
      Config("server.", tags = Tags.of("foo", "bar", "bar", "baz"))
    val classifierFunc = (_: Request[IO]) => Some("classifier[]")
    val meteredRoutes = Micrometer[IO](registry, config).map { micrometer =>
      Metrics[IO](ops = micrometer, classifierF = classifierFunc)(stubRoutes)
    }.unsafeRunSync

    val req = Request[IO](uri = uri("/ok"))
    val resp: Response[IO] = meteredRoutes.orNotFound(req).unsafeRunSync

    resp.status shouldBe Status.Ok
    resp.as[String].unsafeRunSync shouldBe "200 OK"

    testMetersFor(
      registry,
      classifier = "classifier",
      additionalTags = Tags.of("foo", "bar", "bar", "baz")
    )
  }

}
