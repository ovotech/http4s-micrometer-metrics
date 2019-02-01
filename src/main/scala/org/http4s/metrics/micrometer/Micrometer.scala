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

import io.micrometer.core.instrument.{MeterRegistry, Gauge, Timer, Tag, Tags}

case class Config(
    prefix: String,
    tags: Tags = Tags.empty
)

object Micrometer {

  private val TagsReg = """.*?\[([^\]]*)\]""".r
  private val TagReg = """([^:]*)\s*:\s*(.*)""".r

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

      private def namespace(classifier: Option[String]): String = {
        val name = classifier
          .map(_.takeWhile(_ != '[').trim)
          .filter(_.nonEmpty)
          .getOrElse("default")

        s"${prefix}${name}"
      }

      private def tags(classifier: Option[String]): Tags = {
        config.tags and classifier
          .collect {
            case TagsReg(tagsString) if tagsString.trim.nonEmpty =>
              tagsString
                .split(",")
                .collect {
                  case TagReg(key, value) =>
                    Tags.of(key, value)
                }
                .reduce(_ and _)
          }
          .getOrElse(Tags.empty)
      }

      private def activeRequestsGauge(
          classifier: Option[String]): F[AtomicInteger] = {

        val create = for {
          created <- new AtomicInteger(0).pure[F]
          gauge <- F.delay(
            Gauge
              .builder(
                s"${namespace(classifier)}.active-requests",
                created, { x: AtomicInteger =>
                  x.doubleValue
                }
              )
              .tags(tags(classifier))
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

        val methodTags = method match {
          case Method.GET     => Tags.of("method", "get")
          case Method.POST    => Tags.of("method", "post")
          case Method.PUT     => Tags.of("method", "put")
          case Method.PATCH   => Tags.of("method", "patch")
          case Method.HEAD    => Tags.of("method", "head")
          case Method.MOVE    => Tags.of("method", "move")
          case Method.OPTIONS => Tags.of("method", "options")
          case Method.TRACE   => Tags.of("method", "trace")
          case Method.CONNECT => Tags.of("method", "connect")
          case Method.DELETE  => Tags.of("method", "delete")
          case _              => Tags.of("method", "other")
        }

        val allTags = tags(classifier)
          .and(methodTags)

        F.delay(
            Timer
              .builder(s"${namespace(classifier)}.response-headers-time")
              .tags(allTags)
              .register(registry))
          .flatMap { timer =>
            F.delay(timer.record(elapsed, TimeUnit.NANOSECONDS))
          }
      }

      def recordTotalTime(method: org.http4s.Method,
                          status: org.http4s.Status,
                          elapsed: Long,
                          classifier: Option[String]): F[Unit] = {

        val terminationTags = Tags.of("termination", "normal")

        val methodTags = method match {
          case Method.GET     => Tags.of("method", "get")
          case Method.POST    => Tags.of("method", "post")
          case Method.PUT     => Tags.of("method", "put")
          case Method.PATCH   => Tags.of("method", "patch")
          case Method.HEAD    => Tags.of("method", "head")
          case Method.MOVE    => Tags.of("method", "move")
          case Method.OPTIONS => Tags.of("method", "options")
          case Method.TRACE   => Tags.of("method", "trace")
          case Method.CONNECT => Tags.of("method", "connect")
          case Method.DELETE  => Tags.of("method", "delete")
          case _              => Tags.of("method", "other")
        }

        val statusCodeTags = status.code match {
          case hundreds if hundreds < 200 =>
            Tags.of("status-code", "1xx")
          case twohundreds if twohundreds < 300 =>
            Tags.of("status-code", "2xx")
          case threehundreds if threehundreds < 400 =>
            Tags.of("status-code", "3xx")
          case fourhundreds if fourhundreds < 500 =>
            Tags.of("status-code", "4xx")
          case _ =>
            Tags.of("status-code", "5xx")
        }

        val allTags = tags(classifier)
          .and(terminationTags)
          .and(statusCodeTags)
          .and(methodTags)

        recordResponseTime(classifier, allTags, elapsed)
      }

      def recordAbnormalTermination(
          elapsed: Long,
          terminationType: org.http4s.metrics.TerminationType,
          classifier: Option[String]): F[Unit] = {

        val allTags = terminationType match {
          case Abnormal => Tags.of("termination", "abnormal")
          case Error    => Tags.of("termination", "error")
          case Timeout  => Tags.of("termination", "timeout")
        }

        recordResponseTime(classifier, allTags, elapsed)
      }

      private def recordResponseTime(classifier: Option[String],
                                     tags: Tags,
                                     elapsed: Long) = F.delay(
        Timer
          .builder(s"${namespace(classifier)}.response-time")
          .tags(tags)
          .register(registry)
          .record(elapsed, TimeUnit.NANOSECONDS)
      )
    }
  }
}
