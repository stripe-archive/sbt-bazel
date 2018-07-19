package com.stripe.sbt.bazel

import cats.Functor
import cats.Show
import com.stripe.sbt.bazel.BazelDslF._

object `package` {

  type Algebra  [F[_], A] = F[A] =>   A

  type Expr[V]  = Mu[ExprF[V, ?]]

  // This cannot be simplified to `Mu[BazelDslF]` due to some bug with sbt. Maybe a macro issue?
  type BazelDsl = Mu[BazelDslF[?]]

  implicit def showExpr[V: Show]: Show[Expr[V]] =
    Show.show(_ apply ExprF.showAlgebra)

  implicit val functorExpr: Functor[Expr] = new Functor[Expr] {
    def map[A, B](fa: Expr[A])(f: A => B): Expr[B] =
      fa(ExprF.mapAlgebra(f))
  }

  implicit val functorBazelDslF: Functor[BazelDslF] = new Functor[BazelDslF] {
    override def map[A, B](fa: BazelDslF[A])(f: A => B): BazelDslF[B] = fa match {
      case YoloString(s, n) => YoloString(s, f(n))
      case WorkspacePrelude(n) => WorkspacePrelude(f(n))
      case MavenBindings(n) => MavenBindings(f(n))
      case BuildPrelude(n) => BuildPrelude(f(n))
      case BuildTargets(n) => BuildTargets(f(n))
      case Empty => Empty
    }
  }
}
