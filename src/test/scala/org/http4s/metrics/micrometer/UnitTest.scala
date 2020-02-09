package org.http4s.metrics.micrometer

import org.scalactic.source
import org.scalatest._

import org.scalatest.exceptions.TestFailedException
import org.scalatest.exceptions.StackDepthException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

abstract class UnitTest extends AnyFlatSpec with Matchers with EitherValues {

  implicit def convertEitherToValuable[L, R](either: Either[L, R])(
      implicit pos: source.Position
  ): EitherValuable[L, R] = new EitherValuable(either, pos)

  class EitherValuable[L, R](either: Either[L, R], pos: source.Position) {
    def value: R = {
      either.getOrElse(
        throw new TestFailedException(
          (_: StackDepthException) => Some("Either is not a right one"),
          None,
          pos
        )
      )
    }
  }

}
