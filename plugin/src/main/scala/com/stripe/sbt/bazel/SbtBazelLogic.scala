package com.stripe.sbt.bazel

import sbt.Keys
import sbt.Classpaths
import sbt.Def
import sbt.Task
import sbt.librarymanagement.Configuration
import sbt.internal.inc.Analysis
import sbt.internal.inc.Relations

import cats.implicits._

import scala.collection.{ Set => HazySet }
import java.io.{ File => JavaFile }

object SbtBazelLogic extends ExprLogic with ZincLogic

private[bazel] sealed trait ExprLogic {

  val ruleDeps: Def.Initialize[Task[Expr[Keys.Classpath]]] =
    Def.taskDyn(mapToClasspath(SbtBazelKeys.bazelRuleDeps.value))

  val ruleRuntimeDeps: Def.Initialize[Task[Expr[Keys.Classpath]]] =
    Def.taskDyn(mapToClasspath(SbtBazelKeys.bazelRuleRuntimeDeps.value))

  val ruleExports: Def.Initialize[Task[Expr[Keys.Classpath]]] =
    Def.taskDyn(mapToClasspath(SbtBazelKeys.bazelRuleExports.value))

  private val sourceToClasspathTask: Source => Def.Initialize[Task[Keys.Classpath]] = {
    case Source.Evaluate(taskKey) => Def.task(taskKey.value)
    case Source.Empty             => Def.task(Seq.empty: Keys.Classpath)
  }

  private def mapToClasspath(expr: Expr[Source]): Def.Initialize[Task[Expr[Keys.Classpath]]] = Def.taskDyn {
    // Heads up!
    // this is all a giant dance to do:
    //
    //   Def.task(expr.map(sourceToClasspath).map(_.value))
    //
    // ... without getting the SBT dynamic reference error

    val sources: Seq[Source] = expr[Seq[Source]] {
      case Value       (source) => Seq(source)
      case Union       (x, y)   => x ++ y
      case Difference  (x, y)   => x ++ y
      case Intersection(x, y)   => x ++ y
    }

    // hats off to SBT for the most un-ergonomic .sequence.sequence
    val Zoo: Seq[Def.Initialize[Task[Keys.Classpath]]] = sources.map(sourceToClasspathTask)
    val oZo: Def.Initialize[Seq[Task[Keys.Classpath]]] = Def.Initialize.join(Zoo)
    val ooZ: Def.Initialize[Task[Seq[Keys.Classpath]]] = oZo.apply(_.join)

    // also hats off for .map being .apply, but only for Initialize
    ooZ.apply(_
      .map(classpaths => (sources zip classpaths).toMap)
      .map { lookup =>
        expr.map(lookup.get(_).getOrElse(Seq.empty))
      })
    // end of dance
  }

}

private[bazel] sealed trait ZincLogic {

  def zincClasspath(
    config: Configuration,
    f: Relations => HazySet[JavaFile]
  ): Def.Initialize[Task[Keys.Classpath]] = Def.task {
    val fullClasspath = (Keys.fullClasspath in config).value
    Option((Keys.compile in config).value)
      .collect { case a: Analysis => a }
      .toSeq
      .flatMap { analysis =>
        f(analysis.relations)
          .flatMap(f => fullClasspath.find(_.data == f))
      }
  }

  def scalaClasspath(config: Configuration): Def.Initialize[Task[Keys.Classpath]] = Def.task {
    val moduleID = Classpaths.autoLibraryDependency(
      Keys.autoScalaLibrary.value && Keys.scalaHome.value.isEmpty && Keys.managedScalaInstance.value,
      Keys.sbtPlugin.value,
      Keys.scalaOrganization.value,
      Keys.scalaVersion.value)

    val fullClasspath = (Keys.fullClasspath in config).value

    moduleID.flatMap(x =>
      fullClasspath
        .find(_.get(Keys.moduleID.key).fold(false)(y =>
          x.name         == y.name &&
          x.organization == y.organization &&
          x.revision     == y.revision))
        .toSeq)
  }
}
