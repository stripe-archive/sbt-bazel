package com.stripe.sbt.bazel

object BazelAst {
  final case class ScalaLibrary(
    name: String,
    projectDeps: List[String],
    binaryDeps: List[String],
    sources: List[Source],
    visibility: Visibility
  )

  final case class ScalaBinary(
    name: String,
    projectDeps: List[String],
    mainClass: String
  )

  final case class PyFile(exprs: List[PyExpr])

  sealed trait PyExpr

  final case class PyCall(
    funName: String,
    args: List[(String, PyExpr)]
  ) extends PyExpr

  final case class PyStr(str: String) extends PyExpr

  final case class PyArr(arr: List[PyExpr]) extends PyExpr

  final case class PyBinOp(name: String, lhs: PyExpr, rhs: PyExpr) extends PyExpr

  final case class PyAssign(varName: String, pyExpr: PyExpr) extends PyExpr

  final case class PyVar(name: String) extends PyExpr

  final case class PyLoad(label: PyExpr, symbols: List[PyExpr]) extends PyExpr

  def scalaBinary(
    name: String,
    projectDeps: List[String],
    mainClass: String
  ): PyExpr = {
    PyCall(
      "scala_binary",
      List(
        "deps" -> PyArr(projectDeps.map(PyStr)),
        "main_class" -> PyStr(mainClass)
      )
    )
  }

  def scalaLibrary(
    name: String,
    projectDeps: List[String],
    mainClass: String,
    visibility: String,
    sources: List[String]
  ): PyExpr = {
    PyCall(
      "scala_library",
      List(
        "name" -> PyStr(name),
        "deps" -> PyArr(projectDeps.map(PyStr)),
        "visiblity" -> PyStr(visibility),
        "srcs" -> PyArr(sources.map(PyStr))
      )
    )
  }

  def workspacePrelude(scalaRulesVersion: String) = List(
    PyAssign("rules_scala_version", PyStr(scalaRulesVersion)),
    PyCall("http_archive",
      List(
        "name" -> PyStr("io_bazel_rules_scala"),
        "url" -> PyBinOp("%", PyStr("https://github.com/bazelbuild/rules_scala/archive/%s.zip"), PyVar("rules_scala_version")),
        "type" -> PyStr("zip"),
        "strip_prefix" -> PyBinOp("%", PyStr("rules_scala-%s"), PyVar("rules_scala_version"))
      )),
    PyLoad(PyStr("@io_bazel_rules_scala//scala:scala.bzl"),
      List(
        PyStr("scala_repositories")
      )),
    PyCall("scala_repositories", List()),
    PyLoad(PyStr("@io_bazel_rules_scala//scala:toolchains.bzl"),
      List(
        PyStr("scala_register_toolchains")
      )),
    PyCall("scala_register_toolchains", List())
  )

  def buildPrelude: List[PyExpr] = List(
    PyLoad(PyStr("@io_bazel_rules_scala//scala:scala.bzl"),
      List(
        PyStr("scala_binary"),
        PyStr("scala_library"),
        PyStr("scala_test")
      ))
  )

  final case class Dependencies(
    ds: Map[String, Dependency] // org -> Dep
  )

  final case class Dependency(
    packages: Map[String, PackageProps] // package name -> Package
  )

  final case class PackageProps(
    version: String,
    modules: List[String],
    exports: List[String],
    lang: String
  )

  sealed trait Visibility

  object Public extends Visibility

  object Private extends Visibility

  sealed trait Source

  object Source {

    final case class Path(value: String) extends Source

    final case class Glob(sources: List[Source]) extends Source

  }
}
