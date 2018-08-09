package com.stripe.sbt_bazel.example

import com.stripe.sbt_bazel.core.Core

object Main {
  def main(args: Array[String]): Unit = {
    Core.hello("Bazel").unsafeRunSync()
  }
}