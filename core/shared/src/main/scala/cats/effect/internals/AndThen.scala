/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

package cats.effect.internals

import java.io.Serializable

/**
 * Internal API — A type-aligned seq for representing
 * function composition in constant stack space with amortized
 * linear time application (in the number of constituent functions).
 *
 * A variation of this implementation was first introduced in the
 * `cats-effect` project. Implementation is enormously uglier than
 * it should be since `@tailrec` doesn't work properly on functions
 * with existential types.
 *
 * Example:
 *
 * {{{
 *   val seed = AndThen.of((x: Int) => x + 1))
 *   val f = (0 until 10000).foldLeft(seed)((acc, _) => acc.andThen(_ + 1))
 *   // This should not trigger stack overflow ;-)
 *   f(0)
 * }}}
 */
private[effect] sealed abstract class AndThen[-T, +R]
  extends (T => R) with Product with Serializable {

  import AndThen._

  final def apply(a: T): R =
    runLoop(a)

  override def andThen[A](g: R => A): T => A = {
    // Using the fusion technique implemented for `cats.effect.IO#map`
    this match {
      case Single(f, index) if index != IOPlatform.fusionMaxStackDepth =>
        Single(f.andThen(g), index + 1)
      case _ =>
        andThenF(AndThen(g))
    }
  }

  override def compose[A](g: A => T): A => R = {
    // Using the fusion technique implemented for `cats.effect.IO#map`
    this match {
      case Single(f, index) if index != IOPlatform.fusionMaxStackDepth =>
        Single(f.compose(g), index + 1)
      case _ =>
        composeF(AndThen(g))
    }
  }

  private def runLoop(start: T): R = {
    var self: AndThen[Any, Any] = this.asInstanceOf[AndThen[Any, Any]]
    var current: Any = start.asInstanceOf[Any]
    var continue = true

    while (continue) {
      self match {
        case Single(f, _) =>
          current = f(current)
          continue = false

        case Concat(Single(f, _), right) =>
          current = f(current)
          self = right.asInstanceOf[AndThen[Any, Any]]

        case Concat(left @ Concat(_, _), right) =>
          self = left.rotateAccum(right)
      }
    }
    current.asInstanceOf[R]
  }

  private final def andThenF[X](right: AndThen[R, X]): AndThen[T, X] =
    Concat(this, right)
  private final def composeF[X](right: AndThen[X, T]): AndThen[X, R] =
    Concat(right, this)

  // converts left-leaning to right-leaning
  protected final def rotateAccum[E](_right: AndThen[R, E]): AndThen[T, E] = {
    var self: AndThen[Any, Any] = this.asInstanceOf[AndThen[Any, Any]]
    var right: AndThen[Any, Any] = _right.asInstanceOf[AndThen[Any, Any]]
    var continue = true
    while (continue) {
      self match {
        case Concat(left, inner) =>
          self = left.asInstanceOf[AndThen[Any, Any]]
          right = inner.andThenF(right)

        case _ => // Single
          self = self.andThenF(right)
          continue = false
      }
    }
    self.asInstanceOf[AndThen[T, E]]
  }

  override def toString: String =
    "AndThen$" + System.identityHashCode(this)
}

private[effect] object AndThen {
  /** Builds simple [[AndThen]] reference by wrapping a function. */
  def apply[A, B](f: A => B): AndThen[A, B] =
    f match {
      case ref: AndThen[A, B] @unchecked => ref
      case _ => Single(f, 0)
    }

  /** Alias for `apply` that returns a `Function1` type. */
  def of[A, B](f: A => B): (A => B) =
    apply(f)

  private final case class Single[-A, +B](f: A => B, index: Int)
    extends AndThen[A, B]
  private final case class Concat[-A, E, +B](left: AndThen[A, E], right: AndThen[E, B])
    extends AndThen[A, B]
}
