package org.http4s.metrics.micrometer

import org.scalatest._

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

abstract class UnitTest extends AnyFlatSpec with Matchers with EitherValues
