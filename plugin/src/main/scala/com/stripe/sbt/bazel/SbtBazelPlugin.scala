package com.stripe.sbt.bazel

import sbt._
import com.stripe.sbt.bazel.BazelAst._
import java.nio.charset.Charset
import java.nio.file.Files
import java.io.{File => JFile}

import cats.implicits._

import sbt.plugins.JvmPlugin

object SbtBazelPlugin extends AutoPlugin {

  override def requires = JvmPlugin
  override def trigger = allRequirements

  sealed trait ExprDSL {
    implicit def toExprOps[V](x: Expr[V]): ExprOps[V] = new ExprOps(x)
    def Evaluate(taskKey: TaskKey[Keys.Classpath]): Expr[Source] = Mu.embed(Value(Source.Evaluate(taskKey)))
    def Empty: Expr[Source] = Mu.embed(Value(Source.Empty))
    def Deps(config: Configuration): Expr[Source] = Evaluate(config / Keys.dependencyClasspath)
    def UsedDeps(config: Configuration): Expr[Source] = Evaluate(config / SbtBazelKeys.zincLibraryDeps)
    def ScalaLib(config: Configuration): Expr[Source] = Evaluate(config / SbtBazelKeys.scalaLibraryDeps)
  }

  sealed trait KeyAliases {
    def bazelBuildGenerate = SbtBazelKeys.bazelBuildGenerate
    def bazelWorkspaceGenerate = SbtBazelKeys.bazelWorkspaceGenerate
    def bazelMavenRepo = SbtBazelKeys.bazelMavenRepo
    def bazelScalaRulesVersion = SbtBazelKeys.bazelScalaRulesVersion
  }

  object autoImport extends ExprDSL with KeyAliases
  import autoImport._

  override def buildSettings: Seq[Def.Setting[_]] =
    Seq(
      SbtBazelKeys.bazelMavenRepo := None,
    )

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(
      SbtBazelKeys.bazelGenerate          := SbtBazel.bazelGenerateMain.value,
      SbtBazelKeys.bazelBuildGenerate     := true,
      SbtBazelKeys.bazelMavenRepo         := None,
      SbtBazelKeys.bazelWorkspaceGenerate := false,
      SbtBazelKeys.bazelRuleDeps          := UsedDeps(Compile) - ScalaLib(Compile),
      SbtBazelKeys.bazelRuleRuntimeDeps   := Deps(Compile) - UsedDeps(Compile) - ScalaLib(Compile),
      SbtBazelKeys.bazelRuleExports       := Empty,
    ) ++
    inConfig(Compile)(Seq(
      SbtBazelKeys.zincLibraryDeps  := SbtBazelLogic.zincClasspath(Compile, _.allLibraryDeps).value,
      SbtBazelKeys.scalaLibraryDeps := SbtBazelLogic.scalaClasspath(Compile).value
    )) ++
    inConfig(Test)(Seq(
      SbtBazelKeys.zincLibraryDeps  := SbtBazelLogic.zincClasspath(Test, _.allLibraryDeps).value,
      SbtBazelKeys.scalaLibraryDeps := SbtBazelLogic.scalaClasspath(Test).value
    ))
}

object SbtBazelKeys {
  import sbt.{ settingKey, taskKey }

  val bazelGenerate         : TaskKey[Option[File]]      = taskKey("Generate a Bazel build file for this project")
  val bazelBuildGenerate    : SettingKey[Boolean]        = settingKey("Generate BUILD for this project")
  val bazelWorkspaceGenerate: SettingKey[Boolean]        = settingKey("Generate a WORKSPACE file for this project and child projects.")
  val bazelMavenRepo        : SettingKey[Option[String]] = settingKey("URI of maven repository")
  val bazelScalaRulesVersion: SettingKey[String]         = settingKey("SHA of scala-rules version")
  val bazelRuleDeps         : SettingKey[Expr[Source]]   = settingKey("Expression used to assign the deps field")
  val bazelRuleRuntimeDeps  : SettingKey[Expr[Source]]   = settingKey("Expression used to assign the runtime_deps field")
  val bazelRuleExports      : SettingKey[Expr[Source]]   = settingKey("Expression used to assign the exports field")
  val zincLibraryDeps       : TaskKey[Keys.Classpath]    = taskKey("Zinc compute library classpath")
  val scalaLibraryDeps      : TaskKey[Keys.Classpath]    = taskKey("Scala library classpath")
}

object SbtBazel {

  private final val DefaultCharset = Charset.defaultCharset

  private def nameFromString(name: String, configuration: Configuration): String =
    if (configuration == Compile) name else name + "-test"

  private def mavenCoordinates(moduleId: ModuleID): String =
    s"${moduleId.organization}:${moduleId.name}:${moduleId.revision}"

  def replaceWithUnderscore(s: String): String = {
    replaceSet(s, Set("\\.", "-", ":"), '_')
  }

  def replaceWithSlash(s: String): String = {
    replaceSet(s, Set("\\.", "-"), '/')
  }

  def replaceSet(s: String, oldCharsRegex: Set[String], newChar: Char): String = {
    s.replaceAll(oldCharsRegex.mkString("|"), newChar.toString)
  }

  def normalizeJarName(module: ModuleID): String =
    s"${replaceWithUnderscore(module.organization)}_${replaceWithUnderscore(module.name)}_${replaceWithUnderscore(module.revision)}"

  def normalizeBindName(module: ModuleID): String =
    s"jar/${replaceWithSlash(module.organization)}/${replaceWithUnderscore(module.name)}_${replaceWithUnderscore(module.revision)}"

  def mavenJar(module: Attributed[File], repo: String): List[PyExpr] = {
    val Some(moduleId) = module.metadata.get(Keys.moduleID.key)

    val jarName = normalizeJarName(moduleId)
    val bindName = normalizeBindName(moduleId)
    val mvnCoords = mavenCoordinates(moduleId)

    val jar = BazelAst.Helpers.mavenJar(jarName, mvnCoords, repo)

    val bind = BazelAst.Helpers.bind(bindName, s"@$jarName//jar:file")

    List(jar, bind)
  }

  def relativize(base: String, full: String): String = {
    new JFile(new File(base).getAbsolutePath).toURI.relativize(new File(full).toURI).getPath
  }

  lazy val bazelGenerateMain: Def.Initialize[Task[Option[File]]] = Def.task {

    val logger = Keys.streams.value.log
    val ruleDeps: Expr[Keys.Classpath] = SbtBazelLogic.ruleDeps.value
    val ruleRuntimeDeps: Expr[Keys.Classpath] = SbtBazelLogic.ruleRuntimeDeps.value
    val ruleExports: Expr[Keys.Classpath] = SbtBazelLogic.ruleExports.value

    val project = Keys.thisProject.value
    val configuration = Compile
    val baseDirectory = Keys.baseDirectory.value
    val sources = (Keys.sources in Compile).value
    val mainClass = (Keys.mainClass in Compile).value

    val srcs = sources.map { src =>
      relativize(baseDirectory.getAbsolutePath, src.getAbsolutePath)
    }.toList
    val projectName = nameFromString(project.id, configuration)
    val buildFile = baseDirectory / s"BUILD"
    val workspaceFile = baseDirectory / "WORKSPACE"

    val extracted = Project.extract(Keys.state.value)

    val projectDependencies = Keys.thisProject.value.dependencies
      .flatMap { dep =>
        (Keys.baseDirectory in dep.project).get(extracted.structure.data).map { dir =>
          val basePath = (Keys.baseDirectory in ThisBuild).value.getAbsolutePath
          val projectBasePath = dir.getAbsolutePath
          val rel = relativize(basePath, projectBasePath).stripSuffix("/")
          s"//$rel:${dep.project.project}"
        }
      }.toList

    def evaluate(expr: Expr[Keys.Classpath]): List[String] = {
      logger.info(s"expr: ${expr.map(_.toString).show}")
      val moduleExpr: Expr[Set[ModuleID]] =
        expr.map(_.flatMap(_.get(Keys.moduleID.key)).toSet)
      logger.info(s"modules: ${moduleExpr.map(_.toString).show}")
      val labelExpr: Expr[Set[String]] =
        moduleExpr.map(_.map(module => s"//external:${normalizeBindName(module)}"))
      logger.info(s"labels: ${labelExpr.map(_.toString).show}")
      labelExpr(ExprF.evalAlgebra).toList.sorted
    }

    val mavenRepoUrl = SbtBazelKeys.bazelMavenRepo.value
      .orElse((SbtBazelKeys.bazelMavenRepo in ThisBuild).value)
      .getOrElse(sbt.Resolver.DefaultMavenRepositoryRoot)

    val scalaLibrary = BazelAst.Helpers.scalaLibrary(
      projectName,
      projectDependencies ++ evaluate(ruleDeps), // todo: remove separate projectDependencies calculation
      evaluate(ruleRuntimeDeps),
      evaluate(ruleExports),
      "//visibility:public",
      srcs
    )

    val scalaBinary = mainClass.map { m =>
      BazelAst.Helpers.scalaBinary(
        s"$projectName-bin",
        List(s":$projectName"),
        m
      )
    }

    /* Render and write the workspace file */
    if (SbtBazelKeys.bazelWorkspaceGenerate.value) {
      val allProjectDeps =
        Keys.managedClasspath.all(ScopeFilter(projects=inAnyProject, configurations = inConfigurations(Compile)))
          .value
          .flatten
          .distinct
      val externalDepsAst = allProjectDeps.flatMap { dep =>
        mavenJar(dep, mavenRepoUrl)
      }.toList

      val workspaceAst =
        BazelAst.Helpers.workspacePrelude((SbtBazelKeys.bazelScalaRulesVersion in ThisBuild).value) ++ //"63eab9f4d80612e918ba954211f377cc83d27a07") ++
        externalDepsAst
      val workspaceDoc = BazelAst.Render.renderPyExprs(workspaceAst)
      val workspaceRendered = workspaceDoc.renderTrim(0)

      Files.write(workspaceFile.toPath, workspaceRendered.getBytes(DefaultCharset))
    }

    /* Render and write the BUILD file. */
    if (SbtBazelKeys.bazelBuildGenerate.value) {
      val buildAst = BazelAst.Helpers.buildPrelude ++
        List(scalaLibrary) ++
        scalaBinary.fold[List[PyExpr]](List())(b => List(b))
      val buildDoc = BazelAst.Render.renderPyExprs(buildAst)
      val buildRendered = buildDoc.renderTrim(0)
      Files.write(buildFile.toPath, buildRendered.getBytes(DefaultCharset))
    }

    Some(buildFile)
  }
}
