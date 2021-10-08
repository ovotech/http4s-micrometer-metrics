package org.http4s.metrics.micrometer

import scala.concurrent.duration._

import cats.FlatMap
import cats.effect._
import cats.implicits._

import org.http4s.metrics.{MetricsOps, TerminationType}
import org.http4s.metrics.TerminationType._
import org.http4s.{Method, Status}

import io.micrometer.core.instrument.{MeterRegistry, Tags}

import com.ovotech.micrometer.Reporter

case class Config(
    prefix: String,
    tags: Tags = Tags.empty
)

object Micrometer {

  private val TagsReg = """.*?\[([^\]]*)\]""".r
  private val TagReg = """([^:]*)\s*:\s*(.*)""".r

  def apply[F[_]: Async](
      registry: MeterRegistry,
      config: Config
  ): F[MetricsOps[F]] =
    fromRegistry[F](registry, config)

  def fromRegistry[F[_]: Async](
      registry: MeterRegistry,
      config: Config
  ): F[MetricsOps[F]] =
    Reporter
      .fromRegistry[F](registry, config.prefix, config.tags)
      .map(fromReporter(_))

  def fromReporter[F[_]: FlatMap](
      reporter: Reporter[F],
      extraTags: Tags = Tags.empty
  ): MetricsOps[F] =
    new MetricsOps[F] {

      private def namespace(classifier: Option[String]): String = {
        classifier
          .map(_.takeWhile(_ != '[').trim)
          .filter(_.nonEmpty)
          .getOrElse("default")
      }

      private def name(classifier: Option[String], key: String): String =
        s"${namespace(classifier)}.$key"

      private def tags(classifier: Option[String]): Tags = {
        extraTags and classifier
          .collect {
            case TagsReg(tagsString) if tagsString.trim.nonEmpty =>
              tagsString
                .split(",")
                .collect { case TagReg(key, value) =>
                  Tags.of(key, value)
                }
                .reduce(_ and _)
          }
          .getOrElse(Tags.empty)

      }

      def increaseActiveRequests(classifier: Option[String]): F[Unit] =
        reporter.gauge(name(classifier, "active-requests"), tags(classifier)).flatMap(_.increment)

      def decreaseActiveRequests(classifier: Option[String]): F[Unit] =
        reporter.gauge(name(classifier, "active-requests"), tags(classifier)).flatMap(_.decrement)

      def recordHeadersTime(
          method: Method,
          elapsed: Long,
          classifier: Option[String]
      ): F[Unit] =
        reporter
          .timer(
            name(classifier, "response-headers-time"),
            tags(classifier) and methodTags(method)
          )
          .flatMap(_.record(elapsed.nanos))

      def recordAbnormalTermination(
          elapsed: Long,
          terminationType: TerminationType,
          classifier: Option[String]
      ): F[Unit] = {
        val terminationTags = terminationType match {
          case Abnormal(_) => Tags.of("termination", "abnormal")
          case Error(_) => Tags.of("termination", "error")
          case Canceled => Tags.of("termination", "cancelled")
          case Timeout => Tags.of("termination", "timeout")
        }

        recordResponseTime(
          classifier,
          tags(classifier) and terminationTags,
          elapsed
        )
      }
      def recordTotalTime(
          method: Method,
          status: Status,
          elapsed: Long,
          classifier: Option[String]
      ): F[Unit] = {
        val statusTags = status.responseClass match {
          case Status.Informational =>
            Tags.of("status-code", "1xx")
          case Status.Successful =>
            Tags.of("status-code", "2xx")
          case Status.Redirection =>
            Tags.of("status-code", "3xx")
          case Status.ClientError =>
            Tags.of("status-code", "4xx")
          case Status.ServerError =>
            Tags.of("status-code", "5xx")
        }
        val allTags = tags(classifier) and
          Tags.of("termination", "normal") and
          statusTags and
          methodTags(method)

        recordResponseTime(
          classifier,
          allTags,
          elapsed
        )
      }

      private def recordResponseTime(
          classifier: Option[String],
          tags: Tags,
          elapsed: Long
      ): F[Unit] =
        reporter
          .timer(name(classifier, "response-time"), tags)
          .flatMap(_.record(elapsed.nanos))

      private def methodTags(method: Method): Tags =
        Tags.of("method", method.name.toLowerCase)

    }
}
