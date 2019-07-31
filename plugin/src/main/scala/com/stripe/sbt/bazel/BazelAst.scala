package com.stripe.sbt.bazel

import org.typelevel.paiges.Doc

object BazelAst {

  sealed trait PyExpr

  final case class PyCall(
    funName: String,
    args: List[PyExpr] = List(),
    kwargs: List[(String, PyExpr)] = List()
  ) extends PyExpr

  final case class PyCallOn(
    lhs: PyExpr,
    funName: String,
    args: List[PyExpr] = List(),
    kwargs: List[(String, PyExpr)] = List()
  ) extends PyExpr

  final case class PyStr(str: String) extends PyExpr

  final case class PyArr(arr: List[PyExpr]) extends PyExpr

  final case class PyBinOp(name: String, lhs: PyExpr, rhs: PyExpr) extends PyExpr

  final case class PyAssign(varName: String, pyExpr: PyExpr) extends PyExpr

  final case class PyVar(name: String) extends PyExpr

  final case class PyLoad(label: PyExpr, symbols: List[PyExpr]) extends PyExpr

  final case class PyRawString(str: String) extends PyExpr

  object Helpers {
    def scalaBinary(
      name: String,
      projectDeps: List[String],
      mainClass: String
    ): PyExpr = {
      PyCall(
        "scala_binary",
        kwargs = List(
          "name" -> PyStr(name),
          "deps" -> PyArr(projectDeps.map(PyStr)),
          "main_class" -> PyStr(mainClass)
        )
      )
    }

    def scalaLibrary(
      name: String,
      deps: List[String],
      runtimeDeps: List[String],
      exports: List[String],
      visibility: String,
      sources: List[String]
    ): PyExpr = {
      PyCall(
        "scala_library",
        kwargs = List(
          "name" -> PyStr(name),
          "deps" -> PyArr(deps.map(PyStr)),
          "runtime_deps" -> PyArr(runtimeDeps.map(PyStr)),
          "exports" -> PyArr(exports.map(PyStr)),
          "visibility" -> PyArr(List(PyStr(visibility))),
          "srcs" -> PyArr(sources.map(PyStr))
        )
      )
    }

    def bind(name: String, actual: String):PyExpr = {
      PyCall("bind",
      kwargs = List(
          "name" -> PyStr(s"$name"),
          "actual" -> PyStr(actual)
        )
      )
    }

    def mavenJar(jarName: String, mvnCoords: String, repos: List[String]): PyExpr = {
      PyCall("scala_maven_import_external",
      kwargs = List(
          "name" -> PyStr(s"$jarName"),
          "artifact" -> PyStr(s"$mvnCoords"),
          "licenses" -> PyArr(List.empty),
          "server_urls" -> PyArr(repos.map(PyStr))
        )
      )
    }

    val doNotModifyComment = PyRawString("# DO NOT MODIFY. This was automatically generated by sbt-bazel.")

    def workspacePrelude(
      scalaRulesVersion: String,
      protobufVersion: Option[(String, String)],
      skylibVersion: Option[(String, String)],
    ): List[PyExpr] = List(
      doNotModifyComment,
      PyAssign("rules_scala_version", PyStr(scalaRulesVersion)),
      PyLoad(PyStr("@bazel_tools//tools/build_defs/repo:http.bzl"),
        List(
          PyStr("http_archive")
        )
      ),
      PyCall("http_archive",
      kwargs = List(
          "name" -> PyStr("io_bazel_rules_scala"),
          "url" -> PyBinOp("%",
            PyStr("https://github.com/bazelbuild/rules_scala/archive/%s.zip"),
            PyVar("rules_scala_version")
          ),
          "type" -> PyStr("zip"),
          "strip_prefix" -> PyBinOp("%", PyStr("rules_scala-%s"), PyVar("rules_scala_version"))
        )
      ),
      PyLoad(PyStr("@io_bazel_rules_scala//scala:scala.bzl"),
        List(
          PyStr("scala_repositories")
        )
      ),
      PyCall("scala_repositories", List()),
      PyLoad(PyStr("@io_bazel_rules_scala//scala:toolchains.bzl"),
        List(
          PyStr("scala_register_toolchains")
        )
      ),
      PyCall("scala_register_toolchains", List()),
      PyLoad(PyStr("@io_bazel_rules_scala//scala:scala_maven_import_external.bzl"),
        List(
          PyStr("scala_maven_import_external")
        )
      )
    ) ++ protobufVersion.map { case(version, sha256) =>
      List(
        PyAssign("protobuf_version", PyStr(version)),
        PyAssign("protobuf_version_sha256", PyStr(sha256)),
        PyCall("http_archive",
        kwargs = List(
            "name" -> PyStr("com_google_protobuf"),
            "url" -> PyBinOp("%",
              PyStr("https://github.com/protocolbuffers/protobuf/archive/%s.tar.gz"),
              PyVar("protobuf_version")
            ),
            "type" -> PyStr("tar.gz"),
            "sha256" -> PyVar("protobuf_version_sha256")
          )
        )
      )
    }.getOrElse(Nil) ++ skylibVersion.map { case(version, sha256) =>
      List(
        PyAssign("skylib_version", PyStr(version)),
        PyCall("http_archive",
        kwargs = List(
            "name" -> PyStr("bazel_skylib"),
            "url" -> PyCallOn(
              PyStr(s"https://github.com/bazelbuild/bazel-skylib/releases/download/{}/bazel-skylib.{}.tar.gz"),
              "format",
              args = List(PyVar("skylib_version"), PyVar("skylib_version")),
            ),
            "type" -> PyStr("tar.gz"),
            "sha256" -> PyStr(sha256)
          )
        ),
      )
    }.getOrElse(Nil)

    def buildPrelude: List[PyExpr] = List(
      doNotModifyComment,
      PyLoad(PyStr("@io_bazel_rules_scala//scala:scala.bzl"),
        List(
          PyStr("scala_binary"),
          PyStr("scala_library"),
          PyStr("scala_test")
        )
      )
    )
  }

  object Render {
    def renderPyExpr(expr: PyExpr): Doc = {
      expr match {
        case PyStr(s) => str(s)
        case PyArr(ar) => arr(ar.map(renderPyExpr))
        case PyBinOp(name, lhs, rhs) => renderPyExpr(lhs) & Doc.text(name) & renderPyExpr(rhs)
        case PyAssign(varName, rhs) => Doc.text(varName) & Doc.char('=') & renderPyExpr(rhs)
        case PyVar(name) => Doc.text(name)
        case PyLoad(label, symbols) =>
          val args = (label +: symbols).map(renderPyExpr)
          Doc.intercalate(Doc.char(',') + Doc.lineOrEmpty, args)
            .tightBracketBy(Doc.text("load("), Doc.char(')'))
        case PyCall(name, args, kwargs) =>
          val docArgs = args.map(renderPyExpr(_))
          val docKwargs = kwargs.map { case (k, v) =>
            Doc.text(k) -> renderPyExpr(v)
          }
          pyCall(Doc.text(name), docArgs, docKwargs)
        case PyCallOn(lhs, name, args, kwargs) =>
          renderPyExpr(lhs) + Doc.char('.') + renderPyExpr(PyCall(name, args, kwargs))
        case PyRawString(s) => Doc.text(s)
      }
    }

    def renderPyExprs(exprs: List[PyExpr]): Doc = {
      Doc.intercalate(Doc.lineBreak, exprs.map(renderPyExpr))
    }

    def pyKwarg(name: Doc, value: Doc): Doc = {
      name + Doc.space + Doc.char('=') + Doc.space + value
    }

    def pyArgs(args: List[Doc], kwargs: List[(Doc, Doc)]): Doc = {
      Doc.intercalate(
        Doc.char(',') + Doc.lineOrSpace,
        args ++ kwargs.map { case (name, value) => pyKwarg(name, value) }
      )
    }

    def pyCall(name: Doc, args: List[Doc], kwargs: List[(Doc, Doc)]): Doc = {
      val totalArgs = args.length + kwargs.length
      if (totalArgs >= 2) {
        pyArgs(args, kwargs).tightBracketBy(name + Doc.char('('), Doc.char(')'))
      } else if (totalArgs == 1) {
        name + Doc.char('(') + pyArgs(args, kwargs) + Doc.char(')')
      } else {
        name + Doc.text("()")
      }
    }

    def join(docs: List[Doc]): Doc =
      Doc.intercalate(Doc.line, docs.map(_ + Doc.char(',')))

    def str(value: String): Doc = {
      val escaped = value.replaceAll("'", "\\'")
      Doc.text(s"'$escaped'")
    }

    def arr(entries: List[Doc]): Doc =
      if (entries.isEmpty) Doc.text("[]")
      else join(entries).tightBracketBy(Doc.char('['), Doc.char(']'))
  }
}
