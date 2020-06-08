package com.ovotech.micrometer

import java.util.concurrent.atomic.AtomicInteger

import cats.effect.concurrent.Semaphore
import cats.effect.implicits._
import cats.effect.{Sync, Concurrent}
import cats.implicits._
import com.ovotech.micrometer.Reporter._
import io.micrometer.core.instrument.{MeterRegistry, Tags}
import io.micrometer.core.{instrument => micrometer}

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.concurrent.duration._

trait Reporter[F[_]] {
  def counter(name: String): F[Counter[F]] = counter(name, Tags.empty)
  def counter(name: String, tags: Tags): F[Counter[F]]
  def counter(name: String, tags: Map[String, String]): F[Counter[F]] =
    counter(name, tags.toTags)

  def timer(name: String): F[Timer[F]] = timer(name, Tags.empty)
  def timer(name: String, tags: Tags): F[Timer[F]]
  def timer(name: String, tags: Map[String, String]): F[Timer[F]] =
    timer(name, tags.toTags)

  def gauge(name: String): F[Gauge[F]] = gauge(name, Tags.empty)
  def gauge(name: String, tags: Tags): F[Gauge[F]]
  def gauge(name: String, tags: Map[String, String]): F[Gauge[F]] =
    gauge(name, tags.toTags)

  def withExtraTags(extraTags: Tags): Reporter[F]
}

object Reporter {
  trait Counter[F[_]] {
    def increment: F[Unit] = incrementN(1)
    def incrementN(n: Int): F[Unit]
  }

  trait Timer[F[_]] {
    def record(d: FiniteDuration): F[Unit]
  }

  trait Gauge[F[_]] {
    def increment: F[Unit] = incrementN(1)
    def incrementN(n: Int): F[Unit]

    def decrement: F[Unit] = incrementN(-1)
    def decrementN(n: Int): F[Unit] = incrementN(-n)

    /** Run `action` with the gauge incremented before execution and decremented after termination (including error or cancelation) */
    def surround[A](action: F[A]): F[A]

    /** Sets the gauge's value to `n` */
    def setValue(n: Int): F[Unit]
  }

  def fromRegistry[F[_]](
      mx: MeterRegistry,
      metricPrefix: String = "",
      globalTags: Tags = Tags.empty
  )(
      implicit F: Concurrent[F]
  ): F[Reporter[F]] =
    for {
      sem <- Semaphore[F](1)
    } yield new MeterRegistryReporter[F](mx, metricPrefix, globalTags, mutable.Map.empty, sem)

  private class GaugeKey(private val name: String, tags: Tags) {
    private val tagSet: Set[micrometer.Tag] = tags.iterator().asScala.toSet

    override def equals(obj: Any): Boolean = obj match {
      case other: GaugeKey =>
        name == other.name &&
          tagSet == other.tagSet
      case _ => false
    }

    override def hashCode(): Int =
      name.hashCode * 31 + tagSet.hashCode()

    override def toString: String = s"GaugeKey($name, $tags)"
  }

  class MeterRegistryReporter[F[_]](
      mx: MeterRegistry,
      metricPrefix: String,
      globalTags: Tags,
      activeGauges: mutable.Map[GaugeKey, AtomicInteger],
      gaugeSem: Semaphore[F]
  )(
      implicit F: Sync[F]
  ) extends Reporter[F] {
    // local tags overwrite global tags
    private[this] def effectiveTags(tags: Tags) = globalTags and tags

    private[this] val effectivePrefix: String = {
      val trimmed = metricPrefix.trim
      if (trimmed.isEmpty) ""
      else if (trimmed.endsWith(".")) metricPrefix
      else trimmed + "."
    }

    private[this] def metricName(base: String): String =
      effectivePrefix + base

    def counter(name: String, tags: Tags): F[Counter[F]] =
      F.delay {
          micrometer.Counter
            .builder(metricName(name))
            .tags(effectiveTags(tags))
            .register(mx)
        }
        .map { c =>
          new Counter[F] {
            def incrementN(n: Int) =
              F.delay(require(n >= 0)) *> F.delay(c.increment(n.toDouble))
          }
        }

    def timer(name: String, tags: Tags): F[Timer[F]] =
      F.delay {
          micrometer.Timer
            .builder(metricName(name))
            .tags(effectiveTags(tags))
            .register(mx)
        }
        .map { t =>
          new Timer[F] {
            def record(d: FiniteDuration) = F.delay(t.record(d.toNanos, NANOSECONDS))
          }
        }

    def gauge(name: String, tags: Tags): F[Gauge[F]] = {
      val pname = metricName(name)
      val allTags = effectiveTags(tags)

      val create = for {
        created <- F.delay(new AtomicInteger(0))
        _ <- F.delay(
          micrometer.Gauge
            .builder(
              pname,
              created, { x: AtomicInteger => x.doubleValue }
            )
            .tags(allTags)
            .register(mx)
        )

      } yield created

      gaugeSem.withPermit {
        val gaugeKey = new GaugeKey(pname, allTags)
        activeGauges
          .get(gaugeKey)
          .fold {
            create.flatTap(x => F.delay(activeGauges.put(gaugeKey, x)))
          }(_.pure[F])
          .map { g =>
            new Gauge[F] {
              def incrementN(n: Int): F[Unit] =
                F.delay(g.getAndAdd(n)).void

              def surround[A](action: F[A]): F[A] =
                increment.bracket(_ => action)(_ => decrement)

              def setValue(n: Int): F[Unit] = F.delay(g.set(n))
            }
          }
      }
    }

    override def withExtraTags(extraTags: Tags): Reporter[F] =
      new MeterRegistryReporter[F](
        mx,
        metricPrefix,
        globalTags and extraTags,
        activeGauges,
        gaugeSem
      )
  }
}
