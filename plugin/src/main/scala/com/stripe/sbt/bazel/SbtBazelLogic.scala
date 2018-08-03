package com.stripe.sbt.bazel

import java.io.{File => JavaFile}

import cats.implicits._
import com.stripe.sbt.bazel.SbtBazel.relativize
import sbt.Classpaths
import sbt.Def
import sbt.DefaultMavenRepository
import sbt.Keys
import sbt.MavenRepository
import sbt.Project
import sbt.ScopeFilter
import sbt.Task
import sbt.ThisBuild
import sbt.inAnyProject
import sbt.inConfigurations
import sbt.internal.inc.Analysis
import sbt.internal.inc.Relations
import sbt.librarymanagement.Configuration

import scala.collection.{Set => HazySet}

object SbtBazelLogic extends ExprLogic with ZincLogic

private[bazel] sealed trait ExprLogic {

  val ruleDeps: Def.Initialize[Task[Expr[Seq[ProjectDep]]]] =
    Def.taskDyn(mapToProjectDep(SbtBazelKeys.bazelRuleDeps.value))

  val ruleRuntimeDeps: Def.Initialize[Task[Expr[Seq[ProjectDep]]]] =
    Def.taskDyn(mapToProjectDep(SbtBazelKeys.bazelRuleRuntimeDeps.value))

  val ruleExports: Def.Initialize[Task[Expr[Seq[ProjectDep]]]] =
    Def.taskDyn(mapToProjectDep(SbtBazelKeys.bazelRuleExports.value))

  val mavenDeps: Def.Initialize[Task[Expr[Seq[ProjectDep]]]] =
    Def.taskDyn(mapToProjectDep(SbtBazelKeys.bazelMavenDeps.value))

  private def sourceToProjectDep: Source => Def.Initialize[Task[Seq[ProjectDep]]] = {
    case Source.Evaluate(task) => task
    case Source.Pure(v)        => Def.task(Seq(v))
    case Source.Empty          => Def.task(Seq.empty)
  }

  private def mapToProjectDep(expr: Expr[Source]): Def.Initialize[Task[Expr[Seq[ProjectDep]]]] = Def.taskDyn {
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
    val Zoo: Seq[Def.Initialize[Task[Seq[ProjectDep]]]] = sources.map(sourceToProjectDep)
    val oZo: Def.Initialize[Seq[Task[Seq[ProjectDep]]]] = Def.Initialize.join(Zoo)
    val ooZ: Def.Initialize[Task[Seq[Seq[ProjectDep]]]] = oZo.apply(_.join)

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
  ): Def.Initialize[Task[Seq[ProjectDep]]] = Def.task {
    val fullClasspath = (Keys.fullClasspath in config).value
    Option((Keys.compile in config).value)
      .collect { case a: Analysis => a }
      .toSeq
      .flatMap { analysis =>
        f(analysis.relations)
          .flatMap(f => fullClasspath.find(_.data == f))
      }
      .flatMap(_.get(Keys.moduleID.key))
      .map(ProjectDep.ModuleIdDep)
  }

  def scalaClasspath(config: Configuration): Def.Initialize[Task[Seq[ProjectDep]]] = Def.task {
    //TODO: This isn't picking up scala-reflect or scala-xml.
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
      .flatMap(_.get(Keys.moduleID.key))
      .map(ProjectDep.ModuleIdDep)
  }

  def allExternalClasspath(config: Configuration): Def.Initialize[Task[Seq[ProjectDep]]] = {
    val classpath = Keys.externalDependencyClasspath
      .all(ScopeFilter(projects = inAnyProject, configurations = inConfigurations(config)))
      .map(_.flatten)

    val moduleIds = classpath.apply(_.map(_.flatMap(_.get(Keys.moduleID.key))))

    moduleIds.apply(_.map(_.map(ProjectDep.ModuleIdDep)))
  }

  def allMavenResolvers(configuration: Configuration): Def.Initialize[Task[Seq[String]]] = Def.task {
    val additionalResolvers = Keys.resolvers.all(
      ScopeFilter(
        projects = inAnyProject,
        configurations = inConfigurations(configuration)
      )
    ).value

    val resolverUrls = additionalResolvers.flatten.map {
      case r: MavenRepository => r.root
      case r => throw new RuntimeException(s"Unsupported resolver: $r")
    }

    DefaultMavenRepository.root +: resolverUrls
  }

  def internalAndExternalDeps(config: Configuration): Def.Initialize[Task[Seq[ProjectDep]]] = Def.task {
    val extracted = Project.extract(Keys.state.value)

    val internalDeps = Keys.thisProject.value.dependencies
      .flatMap { dep => dep.project
        (Keys.baseDirectory in dep.project).get(extracted.structure.data).map { dir =>
          val basePath = (Keys.baseDirectory in ThisBuild).value.getAbsolutePath
          val projectBasePath = dir.getAbsolutePath
          val rel = relativize(basePath, projectBasePath).stripSuffix("/")
          ProjectDep.BazelDep(s"//$rel", dep.project.project)
        }
      }.toList

    val externalDeps = (config / Keys.externalDependencyClasspath)
      .value
      .flatMap(_.get(Keys.moduleID.key))
      .map(ProjectDep.ModuleIdDep)

    internalDeps ++ externalDeps
  }
}
