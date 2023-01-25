package org.http4s.metrics.micrometer

import scala.concurrent.duration._

import cats.FlatMap
import cats.effect._
import cats.implicits._

import org.http4s.metrics.{MetricsOps, TerminationType}
import org.http4s.metrics.TerminationType._
import org.http4s.{Method, Status}

import io.micrometer.core.instrument.MeterRegistry

import com.ovoenergy.meters4s.{MetricsConfig, Reporter}

case class Config(
    prefix: String,
    tags: Map[String, String] = Map.empty
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
      .fromRegistry[F](registry, MetricsConfig(config.prefix, config.tags))
      .map(fromReporter(_))

  def fromReporter[F[_]: FlatMap](
      reporter: Reporter[F],
      extraTags: Map[String, String] = Map.empty
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

      private def tags(classifier: Option[String]): Map[String, String] = {
        extraTags ++ classifier
          .collect {
            case TagsReg(tagsString) if tagsString.trim.nonEmpty =>
              tagsString
                .split(",")
                .collect { case TagReg(key, value) =>
                  Map(key -> value)
                }
                .reduce(_ ++ _)
          }
          .getOrElse(Map.empty)

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
            tags(classifier) ++ methodTags(method)
          )
          .flatMap(_.record(elapsed.nanos))

      def recordAbnormalTermination(
          elapsed: Long,
          terminationType: TerminationType,
          classifier: Option[String]
      ): F[Unit] = {
        val terminationTags = terminationType match {
          case Abnormal(_) => "termination" -> "abnormal"
          case Error(_) => "termination" -> "error"
          case Canceled => "termination" -> "cancelled"
          case Timeout => "termination" -> "timeout"
        }

        recordResponseTime(
          classifier,
          tags(classifier) ++ Map(terminationTags),
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
          case Status.Informational => "status-code" -> "1xx"
          case Status.Successful => "status-code" -> "2xx"
          case Status.Redirection => "status-code" -> "3xx"
          case Status.ClientError => "status-code" -> "4xx"
          case Status.ServerError => "status-code" -> "5xx"
        }
        val allTags = tags(classifier) ++
          Map("termination" -> "normal", statusTags) ++
          methodTags(method)

        recordResponseTime(
          classifier,
          allTags,
          elapsed
        )
      }

      private def recordResponseTime(
          classifier: Option[String],
          tags: Map[String, String],
          elapsed: Long
      ): F[Unit] =
        reporter
          .timer(name(classifier, "response-time"), tags)
          .flatMap(_.record(elapsed.nanos))

      private def methodTags(method: Method): Map[String, String] = Map(
        "method" -> method.name.toLowerCase
      )

    }
}
