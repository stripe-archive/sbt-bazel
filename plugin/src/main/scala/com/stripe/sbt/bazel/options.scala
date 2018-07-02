package com.stripe.sbt.bazel

import cats._
import cats.implicits._

import sbt.TaskKey
import sbt.Keys
import sbt.Keys.Classpath
import sbt.librarymanagement.Configuration
import sbt.librarymanagement.Configurations.Compile

sealed trait ExprF[V, A]
object ExprF extends ExprFInstances {
  def showAlgebra[V: Show]: ExprF[V, String] => String = {
    case Value       (name) => name.show
    case Union       (x, y) => s"($x ∪ $y)"
    case Difference  (x, y) => s"($x - $y)"
    case Intersection(x, y) => s"($x ∩ $y)"
  }

  def evalAlgebra[A]: ExprF[Set[A], Set[A]] => Set[A] = {
    case Value       (a)    => a
    case Union       (x, y) => x.union(y)
    case Difference  (x, y) => x.diff(y)
    case Intersection(x, y) => x.intersect(y)
  }

  def mapAlgebra[A, B](f: A => B): ExprF[A, Expr[B]] => Expr[B] = {
    case Value       (a)    => Mu.embed(Value(f(a)))
    case Union       (x, y) => Mu.embed(Union(x, y))
    case Difference  (x, y) => Mu.embed(Difference(x, y))
    case Intersection(x, y) => Mu.embed(Intersection(x, y))
  }
}

final case class Value[V, A](value: V) extends ExprF[V, A]
final case class Union[V, A](x: A, y: A) extends ExprF[V, A]
final case class Intersection[V, A](x: A, y: A) extends ExprF[V, A]
final case class Difference[V, A](x: A, y: A) extends ExprF[V, A]

sealed trait Source
object Source {
  case class Evaluate(taskKey: TaskKey[Classpath]) extends Source
  case object Empty extends Source

  implicit val showSource: Show[Source] = Show.fromToString
}

final class ExprOps[V](val x: Expr[V]) extends AnyVal {
  def +(y: Expr[V]): Expr[V] = Mu.embed(Union(x, y))
  def -(y: Expr[V]): Expr[V] = Mu.embed(Difference(x, y))
  def ∪(y: Expr[V]): Expr[V] = Mu.embed(Union(x, y))
  def ∩(y: Expr[V]): Expr[V] = Mu.embed(Intersection(x, y))
}

private[bazel] sealed trait ExprFInstances {
  implicit def functorExprF[V]: Functor[ExprF[V, ?]] =
    new Functor[ExprF[V, ?]] {
      def map[A, B](fa: ExprF[V, A])(f: A => B): ExprF[V, B] =
        fa match {
          case v: Value[_, B @unchecked] => v
          case Union       (x, y)        => Union       (f(x), f(y))
          case Intersection(x, y)        => Intersection(f(x), f(y))
          case Difference  (x, y)        => Difference  (f(x), f(y))
        }
    }
}
