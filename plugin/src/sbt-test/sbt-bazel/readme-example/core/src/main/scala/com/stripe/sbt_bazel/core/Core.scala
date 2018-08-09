package com.stripe.sbt_bazel.core

import cats.effect.IO

object Core {
  def hello(name: String): IO[Unit] = {
    IO(println(s"Hello, $name"))
  }
}
