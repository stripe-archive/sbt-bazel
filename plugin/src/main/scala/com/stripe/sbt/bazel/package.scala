package com.stripe.sbt.bazel

import cats.Functor
import cats.Show

object `package` {

  type Algebra  [F[_], A] = F[A] =>   A

  type Expr[V] = Mu[ExprF[V, ?]]

  implicit def showExpr[V: Show]: Show[Expr[V]] =
    Show.show(_ apply ExprF.showAlgebra)

  implicit val functorExpr: Functor[Expr] = new Functor[Expr] {
    def map[A, B](fa: Expr[A])(f: A => B): Expr[B] =
      fa(ExprF.mapAlgebra(f))
  }
}
