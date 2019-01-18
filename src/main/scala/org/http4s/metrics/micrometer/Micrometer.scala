package org.http4s.metrics.micrometer

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable
import scala.collection.mutable
import scala.collection.JavaConverters._

import cats.Parallel
import cats.implicits._
import cats.effect._
import cats.effect.concurrent._

import org.http4s.{Method, Status}
import org.http4s.metrics.MetricsOps
import org.http4s.metrics.TerminationType
import org.http4s.metrics.TerminationType.{Abnormal, Error, Timeout}

import io.micrometer.core.instrument.{MeterRegistry, Gauge, Timer, Tag}

case class Config(
    prefix: String,
    tags: immutable.Seq[Tag] = List.empty
)

object Micrometer {

  def apply[F[_]]: PartiallyAppliedApply[F] = new PartiallyAppliedApply[F]

  class PartiallyAppliedApply[F[_]] {
    def apply[G[_]](registry: MeterRegistry, config: Config)(
        implicit F: Concurrent[F],
        Par: Parallel[F, G]): F[MetricsOps[F]] = create(registry, config)
  }

  def create[F[_], G[_]](registry: MeterRegistry, config: Config)(
      implicit F: Concurrent[F],
      Par: Parallel[F, G]): F[MetricsOps[F]] = Semaphore[F](1).map { sem =>
    val activeRequestsGauges: mutable.Map[Option[String], AtomicInteger] =
      mutable.Map.empty

    new MetricsOps[F] {

      private val prefix = config.prefix

      private def namespace(classifier: Option[String]): String =
        classifier.map(d => s"${prefix}.${d}").getOrElse(s"${prefix}.default")

      private def activeRequestsGauge(
          classifier: Option[String]): F[AtomicInteger] = {

        val create = for {
          _ <- F.delay(println("Creating gauge"))
          created <- new AtomicInteger(0).pure[F]
          gauge <- F.delay(
            Gauge
              .builder(
                s"${namespace(classifier)}.active-requests",
                created, { x: AtomicInteger =>
                  x.doubleValue
                }
              )
              .tags(config.tags.asJava)
              .register(registry)
          )

        } yield created

        sem.withPermit {
          activeRequestsGauges
            .get(classifier)
            .fold {
              create.flatMap(x =>
                F.delay(activeRequestsGauges.put(classifier, x)) *> x.pure[F])
            }(_.pure[F])
        }
      }

      def decreaseActiveRequests(classifier: Option[String]): F[Unit] =
        activeRequestsGauge(classifier)
          .flatMap(x => F.delay(x.decrementAndGet()))
          .void

      def increaseActiveRequests(classifier: Option[String]): F[Unit] = {
        activeRequestsGauge(classifier)
          .flatMap(x => F.delay(x.incrementAndGet()))
          .void
      }

      def recordHeadersTime(method: org.http4s.Method,
                            elapsed: Long,
                            classifier: Option[String]): F[Unit] = {
        F.delay(
            Timer
              .builder(s"${namespace(classifier)}.requests.headers")
              .tags(config.tags.asJava)
              .register(registry))
          .flatMap { timer =>
            F.delay(timer.record(elapsed, TimeUnit.NANOSECONDS))
          }
      }

      def recordTotalTime(method: org.http4s.Method,
                          status: org.http4s.Status,
                          elapsed: Long,
                          classifier: Option[String]): F[Unit] = {

        // TODO We do the same thing three time ...maybe there is a better way

        val f1 = F
          .delay(
            Timer
              .builder(s"${namespace(classifier)}.requests.total")
              .tags(config.tags.asJava)
              .register(registry))
          .flatMap { timer =>
            F.delay(timer.record(elapsed, TimeUnit.NANOSECONDS))
          }

        val f2 = F
          .delay(
            Timer
              .builder(s"${namespace(classifier)}.${requestTimer(method)}")
              .tags(config.tags.asJava)
              .register(registry))
          .flatMap { timer =>
            F.delay(timer.record(elapsed, TimeUnit.NANOSECONDS))
          }

        val f3 = statusCodeTimer(status, classifier).flatMap { timer =>
          F.delay(timer.record(elapsed, TimeUnit.NANOSECONDS))
        }

        List(f1, f2, f3).parSequence.void
      }

      private def statusCodeTimer(status: Status, classifier: Option[String]) =
        F.delay(status.code match {
          case hundreds if hundreds < 200 =>
            Timer
              .builder(s"${namespace(classifier)}.1xx-responses")
              .tags(config.tags.asJava)
              .register(registry)
          case twohundreds if twohundreds < 300 =>
            Timer
              .builder(s"${namespace(classifier)}.2xx-responses")
              .tags(config.tags.asJava)
              .register(registry)
          case threehundreds if threehundreds < 400 =>
            Timer
              .builder(s"${namespace(classifier)}.3xx-responses")
              .tags(config.tags.asJava)
              .register(registry)
          case fourhundreds if fourhundreds < 500 =>
            Timer
              .builder(s"${namespace(classifier)}.4xx-responses")
              .tags(config.tags.asJava)
              .register(registry)
          case _ =>
            Timer
              .builder(s"${namespace(classifier)}.5xx-responses")
              .tags(config.tags.asJava)
              .register(registry)
        })

      private def requestTimer(method: Method): String = method match {
        case Method.GET     => "get-requests"
        case Method.POST    => "post-requests"
        case Method.PUT     => "put-requests"
        case Method.PATCH   => "patch-requests"
        case Method.HEAD    => "head-requests"
        case Method.MOVE    => "move-requests"
        case Method.OPTIONS => "options-requests"
        case Method.TRACE   => "trace-requests"
        case Method.CONNECT => "connect-requests"
        case Method.DELETE  => "delete-requests"
        case _              => "other-requests"
      }

      def recordAbnormalTermination(
          elapsed: Long,
          terminationType: org.http4s.metrics.TerminationType,
          classifier: Option[String]): F[Unit] = {
        F.delay((terminationType match {
            case Abnormal =>
              Timer.builder(s"${namespace(classifier)}.abnormal-terminations")
            case Error   => Timer.builder(s"${namespace(classifier)}.errors")
            case Timeout => Timer.builder(s"${namespace(classifier)}.timeouts")
          }).tags(config.tags.asJava).register(registry))
          .flatMap(timer =>
            F.delay(timer.record(elapsed, TimeUnit.NANOSECONDS)))
      }
    }
  }
}
