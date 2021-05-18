package com.ovotech

import io.micrometer.core.instrument.{Tags, Tag}

import com.ovotech.micrometer.JdkConverters._

package object micrometer {
  implicit class MapToTag(val value: Map[String, String]) extends AnyVal {
    def toTags = Tags.of(value.map(kv => Tag.of(kv._1, kv._2)).toIterable.asJava)
  }
}
