/*
 * Copyright (c) 2014-2017 by its authors. Some rights reserved.
 * See the project homepage at: https://monix.io
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

package monix.tail.internal

import monix.tail.Iterant
import monix.tail.Iterant.{Halt, Last, Next, NextBatch, NextCursor, Suspend}
import monix.tail.batches.BatchCursor
import monix.types.Applicative
import monix.types.syntax._

import scala.annotation.tailrec
import scala.util.control.NonFatal

private[tail] object IterantDropWhile {
  /**
    * Implementation for `Iterant#dropWhile`
    */
  def apply[F[_], A](source: Iterant[F, A], p: A => Boolean)(implicit F: Applicative[F]): Iterant[F, A] = {
    import F.functor

    // Reusable logic for NextCursor / NextBatch branches
    @tailrec
    def evalCursor(ref: F[Iterant[F, A]], cursor: BatchCursor[A], rest: F[Iterant[F, A]], stop: F[Unit], dropped: Int): Iterant[F, A] = {
      if (!cursor.hasNext())
        Suspend(rest.map(loop), stop)
      else if (dropped >= cursor.recommendedBatchSize)
        Suspend(ref.map(loop), stop)
      else {
        val elem = cursor.next()
        if (p(elem))
          evalCursor(ref, cursor, rest, stop, dropped + 1)
        else if (cursor.hasNext())
          Next(elem, ref, stop)
        else
          Next(elem, rest, stop)
      }
    }

    def loop(source: Iterant[F, A]): Iterant[F, A] = {
      try source match {
        case ref @ Next(item, rest, stop) =>
          if (p(item)) Suspend(rest.map(loop), stop)
          else ref
        case ref @ NextCursor(cursor, rest, stop) =>
          evalCursor(F.pure(ref), cursor, rest, stop, 0)
        case NextBatch(batch, rest, stop) =>
          val cursor = batch.cursor()
          val ref = NextCursor(cursor, rest, stop)
          evalCursor(F.pure(ref), cursor, rest, stop, 0)
        case Suspend(rest, stop) =>
          Suspend(rest.map(loop), stop)
        case last @ Last(elem) =>
          if (p(elem)) Halt(None) else last
        case halt @ Halt(_) =>
          halt
      }
      catch {
        case NonFatal(ex) =>
          val stop = source.earlyStop
          Suspend(stop.map(_ => Halt(Some(ex))), stop)
      }
    }

    // We can have side-effects when executing the predicate,
    // so suspending execution
    Suspend(F.eval(loop(source)), source.earlyStop)
  }
}