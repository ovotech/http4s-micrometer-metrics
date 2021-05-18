package org.http4s.metrics.micrometer

import java.io.IOException
import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import cats.effect.{Timer => CatsEffectTimer, _}

import org.http4s._
import org.http4s.Uri.uri
import org.http4s.dsl.io._
import org.http4s.client._
import org.http4s.client.middleware.Metrics

import io.micrometer.core.instrument.{MeterRegistry, Tags}
import io.micrometer.core.instrument.search.MeterNotFoundException

import org.http4s.metrics.micrometer.util._

class MicrometerClientMetricsSpec extends UnitTest {

  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val cf: ContextShift[IO] = IO.contextShift(ec)
  implicit val timer: CatsEffectTimer[IO] = IO.timer(ec)

  val client = Client.fromHttpApp[IO](HttpApp[IO](stub))

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
        Timer(s"client.${classifier}.response-time", additionalTags and Tags.of("status-code", x))
      )
    }

    allMethods.filter(_ != method).foreach { x =>
      a[MeterNotFoundException] should be thrownBy meterCount(
        registry,
        Timer(s"client.${classifier}.response-time", additionalTags and Tags.of("method", x))
      )

      a[MeterNotFoundException] should be thrownBy meterCount(
        registry,
        Timer(
          s"client.${classifier}.response-headers-time",
          additionalTags and Tags.of("method", x)
        )
      )
    }

    allTerminations.filter(_ != termination).foreach { x =>
      a[MeterNotFoundException] should be thrownBy meterCount(
        registry,
        Timer(s"client.${classifier}.response-time", additionalTags and Tags.of("termination", x))
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
        s"client.${classifier}.response-time",
        additionalTags and responseTimeTags
      )
    ) shouldBe 1

    if (termination == "normal") {
      meterMaxTime(
        registry,
        Timer(
          s"client.${classifier}.response-time",
          additionalTags and responseTimeTags
        )
      ) shouldBe 100.milliseconds

      meterTotalTime(
        registry,
        Timer(
          s"client.${classifier}.response-time",
          additionalTags and responseTimeTags
        )
      ) shouldBe 100.milliseconds

      meterCount(
        registry,
        Timer(
          s"client.${classifier}.response-headers-time",
          additionalTags and Tags.of("method", method)
        )
      ) shouldBe 1

      meterMaxTime(
        registry,
        Timer(
          s"client.${classifier}.response-headers-time",
          additionalTags and Tags.of("method", method)
        )
      ) shouldBe 50.milliseconds

      meterTotalTime(
        registry,
        Timer(
          s"client.${classifier}.response-headers-time",
          additionalTags and Tags.of("method", method)
        )
      ) shouldBe 50.milliseconds
    }

    meterValue(
      registry,
      Gauge(s"client.${classifier}.active-requests", additionalTags)
    ) shouldBe 0
  }

  behavior of "Http client with a micrometer metrics middleware"

  it should "register a 2xx response" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("client.")
    meterRegistryResource
      .use { registry =>
        IO {

          val meteredClient = Micrometer[IO](registry, config)
            .map { micrometer => Metrics[IO](micrometer)(client) }
            .unsafeRunSync()

          val resp = meteredClient.expect[String]("ok").attempt.unsafeRunSync()

          resp.value shouldBe "200 OK"

          testMetersFor(registry)
        }
      }
      .unsafeRunSync()
  }

  it should "register a 4xx response" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("client.")
    meterRegistryResource
      .use { registry =>
        IO {

          val meteredClient = Micrometer[IO](registry, config)
            .map { micrometer => Metrics[IO](micrometer)(client) }
            .unsafeRunSync()

          val resp =
            meteredClient.expect[String]("bad-request").attempt.unsafeRunSync()

          resp.left.value shouldBe UnexpectedStatus(Status(400))

          testMetersFor(registry, statusCode = "4xx")
        }
      }
      .unsafeRunSync()
  }

  it should "register a 5xx response" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("client.")
    meterRegistryResource
      .use { registry =>
        IO {
          val meteredClient = Micrometer[IO](registry, config)
            .map { micrometer => Metrics[IO](micrometer)(client) }
            .unsafeRunSync()

          val resp =
            meteredClient
              .expect[String]("internal-server-error")
              .attempt
              .unsafeRunSync()

          resp.left.value shouldBe UnexpectedStatus(Status(500))

          testMetersFor(registry, statusCode = "5xx")
        }
      }
      .unsafeRunSync()
  }

  it should "register a GET request" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("client.")
    meterRegistryResource
      .use { registry =>
        IO {

          val meteredClient = Micrometer[IO](registry, config)
            .map { micrometer => Metrics[IO](micrometer)(client) }
            .unsafeRunSync()

          val resp = meteredClient.expect[String]("ok").attempt.unsafeRunSync()

          resp.value shouldBe "200 OK"

          testMetersFor(registry, method = "get")
        }
      }
      .unsafeRunSync()
  }

  it should "register a POST request" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("client.")
    meterRegistryResource
      .use { registry =>
        IO {

          val meteredClient = Micrometer[IO](registry, config)
            .map { micrometer => Metrics[IO](micrometer)(client) }
            .unsafeRunSync()

          val resp = meteredClient
            .expect[String](Request[IO](POST, uri("ok")))
            .attempt
            .unsafeRunSync()

          resp.value shouldBe "200 OK"

          testMetersFor(registry, method = "post")
        }
      }
      .unsafeRunSync()
  }

  it should "register a PUT request" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("client.")
    meterRegistryResource
      .use { registry =>
        IO {
          val meteredClient = Micrometer[IO](registry, config)
            .map { micrometer => Metrics[IO](micrometer)(client) }
            .unsafeRunSync()

          val resp = meteredClient
            .expect[String](Request[IO](PUT, uri("ok")))
            .attempt
            .unsafeRunSync()

          resp.value shouldBe "200 OK"

          testMetersFor(registry, method = "put")
        }
      }
      .unsafeRunSync()
  }

  it should "register a PATCH request" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("client.")
    meterRegistryResource
      .use { registry =>
        IO {

          val meteredClient = Micrometer[IO](registry, config)
            .map { micrometer => Metrics[IO](micrometer)(client) }
            .unsafeRunSync()

          val resp = meteredClient
            .expect[String](Request[IO](PATCH, uri("ok")))
            .attempt
            .unsafeRunSync()

          resp.value shouldBe "200 OK"

          testMetersFor(registry, method = "patch")
        }
      }
      .unsafeRunSync()
  }

  it should "register a DELETE request" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("client.")
    meterRegistryResource
      .use { registry =>
        IO {

          val meteredClient = Micrometer[IO](registry, config)
            .map { micrometer => Metrics[IO](micrometer)(client) }
            .unsafeRunSync()

          val resp = meteredClient
            .expect[String](Request[IO](DELETE, uri("ok")))
            .attempt
            .unsafeRunSync()

          resp.value shouldBe "200 OK"

          testMetersFor(registry, method = "delete")
        }
      }
      .unsafeRunSync()
  }

  it should "register a HEAD request" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("client.")
    meterRegistryResource
      .use { registry =>
        IO {
          val meteredClient = Micrometer[IO](registry, config)
            .map { micrometer => Metrics[IO](micrometer)(client) }
            .unsafeRunSync()

          val resp = meteredClient
            .expect[String](Request[IO](HEAD, uri("ok")))
            .attempt
            .unsafeRunSync()

          resp.value shouldBe "200 OK"

          testMetersFor(registry, method = "head")
        }
      }
      .unsafeRunSync()
  }

  it should "register a OPTIONS request" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("client.")
    meterRegistryResource
      .use { registry =>
        IO {

          val meteredClient = Micrometer[IO](registry, config)
            .map { micrometer => Metrics[IO](micrometer)(client) }
            .unsafeRunSync()

          val resp = meteredClient
            .expect[String](Request[IO](OPTIONS, uri("ok")))
            .attempt
            .unsafeRunSync()

          resp.value shouldBe "200 OK"

          testMetersFor(registry, method = "options")
        }
      }
      .unsafeRunSync()
  }

  it should "register an error" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("client.")
    meterRegistryResource
      .use { registry =>
        IO {
          val meteredClient = Micrometer[IO](registry, config)
            .map { micrometer => Metrics[IO](micrometer)(client) }
            .unsafeRunSync()

          val resp = meteredClient
            .expect[String]("error")
            .attempt
            .unsafeRunSync()

          resp.left.value shouldBe an[IOException]

          testMetersFor(registry, termination = "error")
        }
      }
      .unsafeRunSync()
  }

  it should "register a timeout" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("client.")
    meterRegistryResource
      .use { registry =>
        IO {

          val meteredClient = Micrometer[IO](registry, config)
            .map { micrometer => Metrics[IO](micrometer)(client) }
            .unsafeRunSync()

          val resp = meteredClient
            .expect[String]("timeout")
            .attempt
            .unsafeRunSync()

          resp.left.value shouldBe an[TimeoutException]

          testMetersFor(registry, termination = "timeout")
        }
      }
      .unsafeRunSync()
  }

  it should "use the provided request classifier" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("client.")
    meterRegistryResource
      .use { registry =>
        IO {

          val classifierFunc = (_: Request[IO]) => Some("classifier")
          val meteredClient = Micrometer[IO](registry, config)
            .map { micrometer =>
              Metrics[IO](ops = micrometer, classifierF = classifierFunc)(client)
            }
            .unsafeRunSync()

          val resp = meteredClient.expect[String]("ok").attempt.unsafeRunSync()

          resp.value shouldBe "200 OK"

          testMetersFor(registry, classifier = "classifier")
        }
      }
      .unsafeRunSync()
  }

  it should "use tags provided by the request classifier" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("client.")
    meterRegistryResource
      .use { registry =>
        IO {

          val classifierFunc =
            (r: Request[IO]) => Some(s"tagged[num:${r.uri.query.params.getOrElse("num", "")}]")

          val meteredClient = Micrometer[IO](registry, config)
            .map { micrometer =>
              Metrics[IO](ops = micrometer, classifierF = classifierFunc)(client)
            }
            .unsafeRunSync()

          val resp1 = meteredClient.expect[String]("ok?num=one").attempt.unsafeRunSync()
          val resp2 = meteredClient.expect[String]("ok?num=two").attempt.unsafeRunSync()

          resp1 shouldBe Right("200 OK")
          resp2 shouldBe Right("200 OK")

          testMetersFor(registry, classifier = "tagged", additionalTags = Tags.of("num", "one"))
          testMetersFor(registry, classifier = "tagged", additionalTags = Tags.of("num", "two"))
        }
      }
      .unsafeRunSync()
  }

  it should "only record total time and decr active requests after client.run releases" in {
    implicit val clock = FakeClock[IO]
    val config: Config = Config("client.")
    meterRegistryResource
      .use { registry =>
        IO {

          val meteredClient = Micrometer[IO](registry, config)
            .map { micrometer => Metrics[IO](micrometer)(client) }
            .unsafeRunSync()

          meteredClient
            .run(Request[IO](uri = Uri.unsafeFromString("ok")))
            .use { resp =>
              IO {
                EntityDecoder[IO, String]
                  .decode(resp, false)
                  .value
                  .unsafeRunSync()
                  .value shouldBe "200 OK"

                meterValue(registry, Gauge("client.default.active-requests")) shouldBe 1
                meterMaxTime(registry, Timer("client.default.response-headers-time")) shouldBe 50.milliseconds

                a[MeterNotFoundException] should be thrownBy meterCount(
                  registry,
                  Timer(s"client.default.response-time")
                )
              }
            }
            .unsafeRunSync()

          testMetersFor(registry)
        }
      }
      .unsafeRunSync()
  }
}
