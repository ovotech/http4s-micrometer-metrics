/*
 * Copyright 2023 Kaluza Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.metrics.micrometer

import java.io.IOException
import java.util.concurrent.TimeoutException
import scala.concurrent.duration._

import cats.effect._

import org.http4s._
import org.http4s.client._
import org.http4s.client.middleware.Metrics
import org.http4s.dsl.io._
import org.http4s.metrics.micrometer.util._
import org.http4s.syntax.all._
import com.ovoenergy.meters4s.{MetricsConfig, Reporter}
import io.micrometer.core.instrument.search.MeterNotFoundException
import io.micrometer.core.instrument.{MeterRegistry, Tags}

class ClientMetricsSuite extends munit.CatsEffectSuite {

  def resourcesWithClassifier(classifierF: Request[IO] => Option[String] = _ => None) =
    ResourceFunFixture {
      meterRegistryResource.evalMap { registry =>
        val config: MetricsConfig = MetricsConfig("client.")
        Reporter.fromRegistry[IO](registry, config).map { reporter =>
          implicit val clock: Clock[IO] = FakeClock[IO]

          val client = Client.fromHttpApp[IO](HttpApp[IO](stub))
          val metrics = Meters4s[IO](reporter)
          val meteredClient = Metrics[IO](metrics, classifierF)(client)

          (registry, meteredClient)
        }
      }
    }

  val resources = resourcesWithClassifier()

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
      intercept[MeterNotFoundException] {
        meterCount(
          registry,
          Timer(s"client.${classifier}.response-time", additionalTags and Tags.of("status-code", x))
        )
      }
    }

    allMethods.filter(_ != method).foreach { x =>
      intercept[MeterNotFoundException] {
        meterCount(
          registry,
          Timer(s"client.${classifier}.response-time", additionalTags and Tags.of("method", x))
        )
      }

      intercept[MeterNotFoundException] {
        meterCount(
          registry,
          Timer(
            s"client.${classifier}.response-headers-time",
            additionalTags and Tags.of("method", x)
          )
        )
      }
    }

    allTerminations.filter(_ != termination).foreach { x =>
      intercept[MeterNotFoundException] {
        meterCount(
          registry,
          Timer(s"client.${classifier}.response-time", additionalTags and Tags.of("termination", x))
        )
      }
    }

    val responseTimeTags = if (termination != "normal") {
      Tags.of("termination", termination)
    } else {
      Tags.of("status-code", statusCode, "method", method, "termination", termination)
    }

    assertEquals(
      meterCount(
        registry,
        Timer(
          s"client.${classifier}.response-time",
          additionalTags and responseTimeTags
        )
      ),
      1L
    )

    if (termination == "normal") {
      assertEquals(
        meterMaxTime(
          registry,
          Timer(
            s"client.${classifier}.response-time",
            additionalTags and responseTimeTags
          )
        ),
        100.milliseconds
      )

      assertEquals(
        meterTotalTime(
          registry,
          Timer(
            s"client.${classifier}.response-time",
            additionalTags and responseTimeTags
          )
        ),
        100.milliseconds
      )

      assertEquals(
        meterCount(
          registry,
          Timer(
            s"client.${classifier}.response-headers-time",
            additionalTags and Tags.of("method", method)
          )
        ),
        1L
      )

      assertEquals(
        meterMaxTime(
          registry,
          Timer(
            s"client.${classifier}.response-headers-time",
            additionalTags and Tags.of("method", method)
          )
        ),
        50.milliseconds
      )

      assertEquals(
        meterTotalTime(
          registry,
          Timer(
            s"client.${classifier}.response-headers-time",
            additionalTags and Tags.of("method", method)
          )
        ),
        50.milliseconds
      )
    }

    assertEquals(
      meterValue(
        registry,
        Gauge(s"client.${classifier}.active-requests", additionalTags)
      ),
      0d
    )
  }

  resources.test(
    "Http client with a micrometer metrics middleware should register a 2xx response"
  ) { case (registry, meteredClient) =>
    meteredClient
      .statusFromString("/ok")
      .assertEquals(Status.Ok)
      .flatMap(_ => IO(testMetersFor(registry)))
  }

  resources.test(
    "Http client with a micrometer metrics middleware should register a 4xx response"
  ) { case (registry, meteredClient) =>
    meteredClient
      .statusFromString("/bad-request")
      .assertEquals(Status.BadRequest)
      .flatMap(_ => IO(testMetersFor(registry, statusCode = "4xx")))
  }

  resources.test(
    "Http client with a micrometer metrics middleware should register a 5xx response"
  ) { case (registry, meteredClient) =>
    meteredClient
      .statusFromString("/internal-server-error")
      .assertEquals(Status.InternalServerError)
      .flatMap(_ => IO(testMetersFor(registry, statusCode = "5xx")))

  }

  resources.test(
    "Http client with a micrometer metrics middleware should register a GET request"
  ) { case (registry, meteredClient) =>
    meteredClient
      .statusFromString("/ok")
      .assertEquals(Status.Ok)
      .flatMap(_ => IO(testMetersFor(registry, method = "get")))
  }

  resources.test(
    "Http client with a micrometer metrics middleware should register a POST request"
  ) { case (registry, meteredClient) =>
    meteredClient
      .status(Request[IO](POST, uri"/ok"))
      .assertEquals(Status.Ok)
      .flatMap(_ => IO(testMetersFor(registry, method = "post")))
  }

  resources.test("Http client with a micrometer metrics middleware should register a PUT request") {
    case (registry, meteredClient) =>
      meteredClient
        .status(Request[IO](PUT, uri"/ok"))
        .assertEquals(Status.Ok)
        .flatMap(_ => IO(testMetersFor(registry, method = "put")))

  }

  resources.test(
    "Http client with a micrometer metrics middleware should register a PATCH request"
  ) { case (registry, meteredClient) =>
    meteredClient
      .status(Request[IO](PATCH, uri"/ok"))
      .assertEquals(Status.Ok)
      .flatMap(_ => IO(testMetersFor(registry, method = "patch")))
  }

  resources.test(
    "Http client with a micrometer metrics middleware should register a DELETE request"
  ) { case (registry, meteredClient) =>
    meteredClient
      .status(Request[IO](DELETE, uri"/ok"))
      .assertEquals(Status.Ok)
      .flatMap(_ => IO(testMetersFor(registry, method = "delete")))
  }

  resources.test(
    "Http client with a micrometer metrics middleware should register a HEAD request"
  ) { case (registry, meteredClient) =>
    meteredClient
      .status(Request[IO](HEAD, uri"/ok"))
      .assertEquals(Status.Ok)
      .flatMap(_ => IO(testMetersFor(registry, method = "head")))
  }

  resources.test(
    "Http client with a micrometer metrics middleware should register a OPTIONS request"
  ) { case (registry, meteredClient) =>
    meteredClient
      .status(Request[IO](OPTIONS, uri"/ok"))
      .assertEquals(Status.Ok)
      .flatMap(_ => IO(testMetersFor(registry, method = "options")))
  }

  resources.test("Http client with a micrometer metrics middleware should register an error") {
    case (registry, meteredClient) =>
      meteredClient
        .statusFromString("/error")
        .intercept[IOException]
        .flatMap(_ => IO(testMetersFor(registry, termination = "error")))
  }

  resources.test("Http client with a micrometer metrics middleware should register a timeout") {
    case (registry, meteredClient) =>
      meteredClient
        .statusFromString("/timeout")
        .intercept[TimeoutException]
        .flatMap(_ => IO(testMetersFor(registry, termination = "timeout")))
  }

  resourcesWithClassifier((_: Request[IO]) => Some("classifier")).test(
    "Http client with a micrometer metrics middleware should use the provided request classifier"
  ) { case (registry, meteredClient) =>
    meteredClient
      .statusFromString("/ok")
      .assertEquals(Status.Ok)
      .flatMap(_ => IO(testMetersFor(registry, classifier = "classifier")))
  }

  resourcesWithClassifier((r: Request[IO]) =>
    Some(s"tagged[num:${r.uri.query.params.getOrElse("num", "")}]")
  ).test(
    "Http client with a micrometer metrics middleware should use tags provided by the request classifier"
  ) { case (registry, meteredClient) =>
    meteredClient
      .statusFromString("/ok?num=one")
      .assertEquals(Status.Ok)
      .flatMap { _ => meteredClient.statusFromString("/ok?num=two").assertEquals(Status.Ok) }
      .flatMap(_ =>
        IO(testMetersFor(registry, classifier = "tagged", additionalTags = Tags.of("num", "one")))
      )
      .flatMap(_ =>
        IO(testMetersFor(registry, classifier = "tagged", additionalTags = Tags.of("num", "two")))
      )
  }

  resources.test(
    "Http client with a micrometer metrics middleware should only record total time and decr active requests after client.run releases"
  ) { case (registry, meteredClient) =>
    meteredClient
      .run(Request[IO](uri = Uri.unsafeFromString("/ok")))
      .use { resp =>
        IO(assertEquals(resp.status, Status.Ok))
          .flatMap { _ =>
            IO(assertEquals(meterValue(registry, Gauge("client.default.active-requests")), 1d))
          }
          .flatMap { _ =>
            IO(
              assertEquals(
                meterMaxTime(
                  registry,
                  Timer("client.default.response-headers-time")
                ),
                50.milliseconds
              )
            )
          }
          .flatMap { _ =>
            IO(
              meterCount(
                registry,
                Timer(s"client.default.response-time")
              )
            ).intercept[MeterNotFoundException]
          }
      }

  }
}
