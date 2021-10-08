package org.http4s.metrics.micrometer

import java.io.IOException
import java.util.concurrent.{TimeUnit, TimeoutException}

import scala.concurrent.duration._

import cats.effect._
import fs2.Stream

import org.http4s.{Request, Response}
import org.http4s.dsl.io._
import org.http4s.Method.GET

import io.micrometer.core.instrument.{MeterRegistry, Tags}
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import cats.Applicative
import org.http4s.HttpRoutes

object util {

  def stub: PartialFunction[Request[IO], IO[Response[IO]]] = {
    case (HEAD | GET | POST | PUT | PATCH | DELETE | OPTIONS | TRACE | CONNECT) -> Root / "ok" =>
      Ok("200 OK")
    case _ -> Root / "bad-request" =>
      BadRequest("400 Bad Request")
    case _ -> Root / "internal-server-error" =>
      InternalServerError("500 Internal Server Error")
    case _ -> Root / "error" =>
      IO.raiseError[Response[IO]](new IOException("error"))
    case _ -> Root / "timeout" =>
      IO.raiseError[Response[IO]](new TimeoutException("request timed out"))
    case _ -> Root / "abnormal-termination" =>
      Ok("200 OK").map(
        _.withBodyStream(Stream.raiseError[IO](new RuntimeException("Abnormal termination")))
      )
    case r =>
      NotFound("404 Not Found")
  }

  def meterValue(registry: MeterRegistry, meter: Gauge): Double =
    registry.get(meter.name).tags(meter.tags).gauge().value

  def meterCount(registry: MeterRegistry, meter: Counter): Double =
    registry.get(meter.name).tags(meter.tags).counter().count

  def meterCount(registry: MeterRegistry, meter: Timer): Long =
    registry.get(meter.name).tags(meter.tags).timer().count

  def meterTotalTime(registry: MeterRegistry, meter: Timer): FiniteDuration =
    FiniteDuration(
      registry
        .get(meter.name)
        .tags(meter.tags)
        .timer()
        .totalTime(TimeUnit.NANOSECONDS)
        .toLong,
      TimeUnit.NANOSECONDS
    )

  def meterMeanTime(registry: MeterRegistry, meter: Timer): FiniteDuration =
    FiniteDuration(
      registry
        .get(meter.name)
        .tags(meter.tags)
        .timer()
        .mean(TimeUnit.NANOSECONDS)
        .toLong,
      TimeUnit.NANOSECONDS
    )

  def meterMaxTime(registry: MeterRegistry, meter: Timer): FiniteDuration =
    FiniteDuration(
      registry
        .get(meter.name)
        .tags(meter.tags)
        .timer()
        .max(TimeUnit.NANOSECONDS)
        .toLong,
      TimeUnit.NANOSECONDS
    )

  case class Gauge(name: String, tags: Tags = Tags.empty)
  case class Counter(name: String, tags: Tags = Tags.empty)
  case class Timer(name: String, tags: Tags = Tags.empty)

  object FakeClock {
    def apply[F[_]: Sync] = new Clock[F] {
      private var count = 0L

      def applicative: Applicative[F] = Sync[F]

      override def realTime: F[FiniteDuration] = Sync[F].delay {
        count += 50
        count.milliseconds
      }

      override def monotonic: F[FiniteDuration] = Sync[F].delay {
        count += 50
        count.milliseconds
      }
    }
  }

  val meterRegistryResource: Resource[IO, SimpleMeterRegistry] =
    Resource.make(IO(new SimpleMeterRegistry))(r => IO(r.close))
}
