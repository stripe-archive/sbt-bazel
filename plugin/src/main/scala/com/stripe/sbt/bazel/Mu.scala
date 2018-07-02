package com.stripe.sbt.bazel

import cats.~>
import cats.Functor
import cats.Id
import cats.syntax.functor._

// forall A. Algebra[F, A] => A

abstract class Mu[F[_]] extends (λ[α => F[α] => α] ~> Id)

object Mu {
  def embed[F[_]: Functor](fmu: F[Mu[F]]): Mu[F] =
    new Mu[F] {
      def apply[A](fold: F[A] => A): Id[A] =
        fold(fmu.map(mu => mu(fold)))
    }
}
