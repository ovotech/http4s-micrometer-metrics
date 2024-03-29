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

import scala.concurrent.duration._

import cats.effect._

import org.http4s._
import org.http4s.dsl.io._
import org.http4s.metrics.micrometer.util._
import org.http4s.server.middleware.Metrics
import org.http4s.syntax.all._
import com.ovoenergy.meters4s.{MetricsConfig, Reporter}
import io.micrometer.core.instrument.search.MeterNotFoundException
import io.micrometer.core.instrument.{MeterRegistry, Tags}

class MicrometerServerMetricsSuite extends munit.CatsEffectSuite {

  def resourcesWithParams(
      tags: Map[String, String] = Map.empty,
      classifierF: Request[IO] => Option[String] = _ => None
  ) =
    ResourceFunFixture {
      meterRegistryResource.evalMap { registry =>
        val config: MetricsConfig = MetricsConfig("server.", tags)
        Reporter.fromRegistry[IO](registry, config).map { reporter =>
          implicit val clock: Clock[IO] = FakeClock[IO]

          val metrics = Meters4s[IO](reporter)

          val stubRoutes = HttpRoutes.of[IO](stub)
          val meteredStubRoutes = Metrics[IO](metrics, classifierF = classifierF)(stubRoutes)

          (registry, meteredStubRoutes)
        }
      }
    }

  val resources = resourcesWithParams()

  // val stubRoutes = HttpRoutes.of[IO](stub)

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
          Timer(s"server.${classifier}.response-time", additionalTags and Tags.of("status-code", x))
        )
      }
    }

    allMethods.filter(_ != method).foreach { x =>
      intercept[MeterNotFoundException] {
        meterCount(
          registry,
          Timer(s"server.${classifier}.response-time", additionalTags and Tags.of("method", x))
        )
      }

      intercept[MeterNotFoundException] {
        meterCount(
          registry,
          Timer(
            s"server.${classifier}.response-headers-time",
            additionalTags and Tags.of("method", x)
          )
        )
      }
    }

    allTerminations.filter(_ != termination).foreach { x =>
      intercept[MeterNotFoundException] {
        meterCount(
          registry,
          Timer(s"server.${classifier}.response-time", additionalTags and Tags.of("termination", x))
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
          s"server.${classifier}.response-time",
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
            s"server.${classifier}.response-time",
            additionalTags and responseTimeTags
          )
        ),
        100.milliseconds
      )

      assertEquals(
        meterTotalTime(
          registry,
          Timer(
            s"server.${classifier}.response-time",
            additionalTags and responseTimeTags
          )
        ),
        100.milliseconds
      )
    }

    assertEquals(
      meterCount(
        registry,
        Timer(
          s"server.${classifier}.response-headers-time",
          additionalTags and Tags.of("method", method)
        )
      ),
      1L
    )

    assertEquals(
      meterMaxTime(
        registry,
        Timer(
          s"server.${classifier}.response-headers-time",
          additionalTags and Tags.of("method", method)
        )
      ),
      50.milliseconds
    )

    assertEquals(
      meterTotalTime(
        registry,
        Timer(
          s"server.${classifier}.response-headers-time",
          additionalTags and Tags.of("method", method)
        )
      ),
      50.milliseconds
    )

    assertEquals(
      meterValue(
        registry,
        Gauge(s"server.${classifier}.active-requests", additionalTags)
      ),
      0d
    )
  }

  resources.test(
    "Http routes with a micrometer metrics middleware should register a 2xx response"
  ) { case (registry, meteredRoutes) =>
    meteredRoutes
      .orNotFound(Request[IO](uri = uri"/ok"))
      .flatMap(r => r.bodyText.compile.lastOrError.as(r.status))
      .assertEquals(Status.Ok)
      .flatMap(_ => IO(testMetersFor(registry, "get", "2xx")))
  }

  resources.test(
    "Http routes with a micrometer metrics middleware should register a 4xx response"
  ) { case (registry, meteredRoutes) =>
    meteredRoutes
      .orNotFound(Request[IO](uri = uri"/bad-request"))
      .flatMap(r => r.bodyText.compile.lastOrError.as(r.status))
      .assertEquals(Status.BadRequest)
      .flatMap(_ => IO(testMetersFor(registry, "get", "4xx")))
  }

  resources.test(
    "Http routes with a micrometer metrics middleware should register a 5xx response"
  ) { case (registry, meteredRoutes) =>
    meteredRoutes
      .orNotFound(Request[IO](uri = uri"/internal-server-error"))
      .flatMap(r => r.bodyText.compile.lastOrError.as(r.status))
      .assertEquals(Status.InternalServerError)
      .flatMap(_ => IO(testMetersFor(registry, "get", "5xx")))

  }

  resources.test(
    "Http routes with a micrometer metrics middleware should register a POST request"
  ) { case (registry, meteredRoutes) =>
    meteredRoutes
      .orNotFound(Request[IO](method = POST, uri = uri"/ok"))
      .flatMap(r => r.bodyText.compile.lastOrError.as(r.status))
      .assertEquals(Status.Ok)
      .flatMap(_ => IO(testMetersFor(registry, "post", "2xx")))
  }

  resources.test(
    "Http routes with a micrometer metrics middleware should register a PUT request"
  ) { case (registry, meteredRoutes) =>
    meteredRoutes
      .orNotFound(Request[IO](method = PUT, uri = uri"/ok"))
      .flatMap(r => r.bodyText.compile.lastOrError.as(r.status))
      .assertEquals(Status.Ok)
      .flatMap(_ => IO(testMetersFor(registry, "put", "2xx")))
  }

  resources.test(
    "Http routes with a micrometer metrics middleware should register a PATCH request"
  ) { case (registry, meteredRoutes) =>
    meteredRoutes
      .orNotFound(Request[IO](method = PATCH, uri = uri"/ok"))
      .flatMap(r => r.bodyText.compile.lastOrError.as(r.status))
      .assertEquals(Status.Ok)
      .flatMap(_ => IO(testMetersFor(registry, "patch", "2xx")))
  }

  resources.test(
    "Http routes with a micrometer metrics middleware should register a DELETE request"
  ) { case (registry, meteredRoutes) =>
    meteredRoutes
      .orNotFound(Request[IO](method = DELETE, uri = uri"/ok"))
      .flatMap(r => r.bodyText.compile.lastOrError.as(r.status))
      .assertEquals(Status.Ok)
      .flatMap(_ => IO(testMetersFor(registry, "delete", "2xx")))
  }

  resources.test(
    "Http routes with a micrometer metrics middleware should register a HEAD request"
  ) { case (registry, meteredRoutes) =>
    meteredRoutes
      .orNotFound(Request[IO](method = HEAD, uri = uri"/ok"))
      .flatMap(r => r.bodyText.compile.lastOrError.as(r.status))
      .assertEquals(Status.Ok)
      .flatMap(_ => IO(testMetersFor(registry, "head", "2xx")))
  }

  resources.test(
    "Http routes with a micrometer metrics middleware should register a OPTIONS request"
  ) { case (registry, meteredRoutes) =>
    meteredRoutes
      .orNotFound(Request[IO](method = OPTIONS, uri = uri"/ok"))
      .flatMap(r => r.bodyText.compile.lastOrError.as(r.status))
      .assertEquals(Status.Ok)
      .flatMap(_ => IO(testMetersFor(registry, "options", "2xx")))
  }

  resources.test(
    "Http routes with a micrometer metrics middleware should register an error"
  ) { case (registry, meteredRoutes) =>
    meteredRoutes
      .orNotFound(Request[IO](uri = uri"/error"))
      .intercept[Throwable]
      .flatMap(_ => IO(testMetersFor(registry, statusCode = "5xx", termination = "error")))
  }

  resources.test(
    "Http routes with a micrometer metrics middleware should register an abnormal termination"
  ) { case (registry, meteredRoutes) =>
    meteredRoutes
      .orNotFound(Request[IO](uri = uri"/abnormal-termination"))
      .flatMap(resp => resp.body.attempt.compile.lastOrError)
      .flatMap(_ => IO(testMetersFor(registry, termination = "abnormal")))
  }

  // // TODO how to simulate a timeout???

  resourcesWithParams(classifierF = (_: Request[IO]) => Some("classifier")).test(
    "Http routes with a micrometer metrics middleware should use the provided request classifier"
  ) { case (registry, meteredRoutes) =>
    meteredRoutes
      .orNotFound(Request[IO](uri = uri"/ok"))
      .flatMap(r => r.bodyText.compile.lastOrError.as(r.status))
      .assertEquals(Status.Ok)
      .flatMap(_ => IO(testMetersFor(registry, classifier = "classifier")))
  }

  resourcesWithParams(tags = Map("foo" -> "bar")).test(
    "Http routes with a micrometer metrics middleware should tags metrics using global tags"
  ) { case (registry, meteredRoutes) =>
    meteredRoutes
      .orNotFound(Request[IO](uri = uri"/ok"))
      .flatMap(r => r.bodyText.compile.lastOrError.as(r.status))
      .assertEquals(Status.Ok)
      .flatMap(_ => IO(testMetersFor(registry, additionalTags = Tags.of("foo", "bar"))))
  }

  resourcesWithParams(
    tags = Map("foo" -> "bar", "bar" -> "baz"),
    classifierF = (_: Request[IO]) => Some("classifier[bar:bazv2,baz:bar]")
  ).test(
    "Http routes with a micrometer metrics middleware should use the provided request classifier to overwrite the tags"
  ) { case (registry, meteredRoutes) =>
    meteredRoutes
      .orNotFound(Request[IO](uri = uri"/ok"))
      .flatMap(r => r.bodyText.compile.lastOrError.as(r.status))
      .assertEquals(Status.Ok)
      .flatMap(_ =>
        IO(
          testMetersFor(
            registry,
            classifier = "classifier",
            additionalTags = Tags.of("foo", "bar", "bar", "bazv2", "baz", "bar")
          )
        )
      )
  }

  resourcesWithParams(
    tags = Map("foo" -> "bar", "bar" -> "baz"),
    classifierF = (_: Request[IO]) => Some("[bar:bazv2,baz:bar]")
  ).test(
    "Http routes with a micrometer metrics middleware should use the provided request empty classifier to overwrite the tags"
  ) { case (registry, meteredRoutes) =>
    meteredRoutes
      .orNotFound(Request[IO](uri = uri"/ok"))
      .flatMap(r => r.bodyText.compile.lastOrError.as(r.status))
      .assertEquals(Status.Ok)
      .flatMap(_ =>
        IO(
          testMetersFor(
            registry,
            additionalTags = Tags.of("foo", "bar", "bar", "bazv2", "baz", "bar")
          )
        )
      )
  }

  resourcesWithParams(
    tags = Map("foo" -> "bar", "bar" -> "baz"),
    classifierF = (_: Request[IO]) => Some("classifier[]")
  ).test(
    "Http routes with a micrometer metrics middleware should handle classifier with empty tags"
  ) { case (registry, meteredRoutes) =>
    meteredRoutes
      .orNotFound(Request[IO](uri = uri"/ok"))
      .flatMap(r => r.bodyText.compile.lastOrError.as(r.status))
      .assertEquals(Status.Ok)
      .flatMap(_ =>
        IO(
          testMetersFor(
            registry,
            classifier = "classifier",
            additionalTags = Tags.of("foo", "bar", "bar", "baz")
          )
        )
      )
  }

  resourcesWithParams(
    tags = Map("foo" -> "bar", "bar" -> "baz"),
    classifierF = (_: Request[IO]) => Some("classifier")
  ).test(
    "Http routes with a micrometer metrics middleware should handle classifier with no tags"
  ) { case (registry, meteredRoutes) =>
    meteredRoutes
      .orNotFound(Request[IO](uri = uri"/ok"))
      .flatMap(r => r.bodyText.compile.lastOrError.as(r.status))
      .assertEquals(Status.Ok)
      .flatMap(_ =>
        IO(
          testMetersFor(
            registry,
            classifier = "classifier",
            additionalTags = Tags.of("foo", "bar", "bar", "baz")
          )
        )
      )
  }

  resourcesWithParams(
    classifierF = (_: Request[IO]) => Some("classifier[ ]")
  ).test(
    "Http routes with a micrometer metrics middleware should handle blank tags"
  ) { case (registry, meteredRoutes) =>
    meteredRoutes
      .orNotFound(Request[IO](uri = uri"/ok"))
      .flatMap(r => r.bodyText.compile.lastOrError.as(r.status))
      .assertEquals(Status.Ok)
      .flatMap(_ =>
        IO(
          testMetersFor(
            registry,
            classifier = "classifier",
            additionalTags = Tags.empty
          )
        )
      )
  }

}
