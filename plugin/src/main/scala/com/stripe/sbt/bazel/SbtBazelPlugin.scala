package com.stripe.sbt.bazel

import java.io.{File => JFile}
import java.nio.charset.Charset
import java.nio.file.Files

import cats.implicits._
import com.stripe.sbt.bazel.BazelAst._
import sbt.Compile
import sbt.Def
import sbt._
import sbt.plugins.JvmPlugin

object SbtBazelPlugin extends AutoPlugin {

  override def requires = JvmPlugin
  override def trigger = allRequirements

  sealed trait ExprDSL {
    implicit def toExprOps[V](x: Expr[V]): ExprOps[V] = new ExprOps(x)
    def Evaluate[A](task: Def.Initialize[Task[Seq[ProjectDep]]]): Expr[Source] = Mu.embed(Value(Source.Evaluate(task)))
    def Empty: Expr[Source] = Mu.embed(Value(Source.Empty))
    def Deps(config: Configuration): Expr[Source] = Evaluate(SbtBazelLogic.internalAndExternalDeps(config))
    def UsedDeps(config: Configuration): Expr[Source] = Evaluate(config / SbtBazelKeys.zincLibraryDeps)
    def ScalaLib(config: Configuration): Expr[Source] = Evaluate(config / SbtBazelKeys.scalaLibraryDeps)
    def Dep(moduleId: ModuleID): Expr[Source] = Mu.embed(Value(Source.Pure(ProjectDep.ModuleIdDep(moduleId))))
    def ManualDep(dep: String): Expr[Source] = Mu.embed(Value(Source.Pure(ProjectDep.ManualDep(dep))))
    def AllExternalDeps(config: Configuration): Expr[Source] = Evaluate(SbtBazelLogic.allExternalClasspath(config))
  }
  object ExprDSL extends ExprDSL

  sealed trait BazelDSL {
    import BazelDslF._
    implicit def toBazelDslOps(x: BazelDsl): BazelDSLOps = new BazelDSLOps(x)
    val empty: BazelDsl = Mu.embed[BazelDslF](Empty)
    val mavenBindings: BazelDsl = Mu.embed(MavenBindings(empty))
    val workspacePrelude: BazelDsl = Mu.embed(WorkspacePrelude(empty))
    def yoloString(str: String): BazelDsl = Mu.embed(YoloString(str, empty))
    val buildPrelude: BazelDsl = Mu.embed(BuildPrelude(empty))
    def buildTargets: BazelDsl = Mu.embed(BuildTargets(empty))
  }

  sealed trait KeyAliases {
    def bazelBuildGenerate = SbtBazelKeys.bazelBuildGenerate
    def bazelWorkspaceGenerate = SbtBazelKeys.bazelWorkspaceGenerate
    def bazelMavenRepo = SbtBazelKeys.bazelMavenRepo
    def bazelScalaRulesVersion = SbtBazelKeys.bazelScalaRulesVersion
    def bazelCustomWorkspace = SbtBazelKeys.bazelCustomWorkspace
    def bazelCustomBuild = SbtBazelKeys.bazelCustomBuild
    def bazelRuleDeps = SbtBazelKeys.bazelRuleDeps
    def bazelMavenDeps = SbtBazelKeys.bazelMavenDeps
  }

  object autoImport extends ExprDSL with KeyAliases with BazelDSL

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
      SbtBazelKeys.bazelRuleDeps          := Deps(Compile) - ScalaLib(Compile),
      SbtBazelKeys.bazelRuleRuntimeDeps   := Empty,
      SbtBazelKeys.bazelRuleExports       := Empty,
      SbtBazelKeys.bazelMavenDeps         := AllExternalDeps(Compile),
      SbtBazelKeys.bazelCustomWorkspace   := workspacePrelude +: mavenBindings,
      SbtBazelKeys.bazelCustomBuild       := buildPrelude +: buildTargets
    ) ++
    inConfig(Compile)(Seq(
      SbtBazelKeys.zincLibraryDeps   := SbtBazelLogic.zincClasspath(Compile, _.allLibraryDeps).value,
      SbtBazelKeys.scalaLibraryDeps  := SbtBazelLogic.scalaClasspath(Compile).value,
      SbtBazelKeys.allMavenResolvers := SbtBazelLogic.allMavenResolvers(Compile).value
    )) ++
    inConfig(Test)(Seq(
      SbtBazelKeys.zincLibraryDeps   := SbtBazelLogic.zincClasspath(Test, _.allLibraryDeps).value,
      SbtBazelKeys.scalaLibraryDeps  := SbtBazelLogic.scalaClasspath(Test).value,
      SbtBazelKeys.allMavenResolvers := SbtBazelLogic.allMavenResolvers(Test).value
    ))
}

object SbtBazelKeys {


  import sbt.settingKey
  import sbt.taskKey

  val bazelCustomBuild      : SettingKey[BazelDsl]       = settingKey("Custom BUILD file for the project")
  val bazelGenerate         : TaskKey[Option[File]]      = taskKey("Generate a Bazel build file for this project")
  val bazelBuildGenerate    : SettingKey[Boolean]        = settingKey("Generate BUILD for this project")
  val bazelWorkspaceGenerate: SettingKey[Boolean]        = settingKey("Generate a WORKSPACE file for this project and child projects.")
  val bazelMavenRepo        : SettingKey[Option[String]] = settingKey("URI of maven repository")
  val bazelScalaRulesVersion: SettingKey[String]         = settingKey("SHA of scala-rules version")
  val bazelRuleDeps         : SettingKey[Expr[Source]]   = settingKey("Expression used to assign the deps field")
  val bazelRuleRuntimeDeps  : SettingKey[Expr[Source]]   = settingKey("Expression used to assign the runtime_deps field")
  val bazelRuleExports      : SettingKey[Expr[Source]]   = settingKey("Expression used to assign the exports field")
  val bazelMavenDeps        : SettingKey[Expr[Source]]   = settingKey("Expression used to assign external maven deps")
  val zincLibraryDeps       : TaskKey[Seq[ProjectDep]]    = taskKey("Zinc compute library classpath")
  val scalaLibraryDeps      : TaskKey[Seq[ProjectDep]]    = taskKey("Scala library classpath")
  val bazelCustomWorkspace  : SettingKey[BazelDsl]       = settingKey("Customize the generated WORKSPACE file.")
  val allMavenResolvers     : TaskKey[Seq[String]]       = taskKey("All maven resolver URLs.")
}

object SbtBazel {

  private final val DefaultCharset = Charset.defaultCharset

  private def nameFromString(name: String, configuration: Configuration): String =
    if (configuration == Compile) name else name + "-test"

  private def mavenCoordinates(moduleId: ModuleID, scalaVersion: String): String = {
    moduleId.crossVersion match {
      case _ : Binary => s"${moduleId.organization}:${moduleId.name}_$scalaVersion:${moduleId.revision}"
      case _ => s"${moduleId.organization}:${moduleId.name}:${moduleId.revision}"
    }


  }

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

  def mavenJar(moduleId: ModuleID, repo: List[String], scalaVersion: String): List[PyExpr] = {

    val jarName = normalizeJarName(moduleId)
    val bindName = normalizeBindName(moduleId)
    val mvnCoords = mavenCoordinates(moduleId, scalaVersion)

    val jar = BazelAst.Helpers.mavenJar(jarName, mvnCoords, repo)

    val bind = BazelAst.Helpers.bind(bindName, s"@$jarName//jar")

    List(jar, bind)
  }

  def relativize(base: String, full: String): String = {
    new JFile(new File(base).getAbsolutePath).toURI.relativize(new File(full).toURI).getPath
  }

  lazy val bazelGenerateMain: Def.Initialize[Task[Option[File]]] = Def.task {

    val logger = Keys.streams.value.log
    val ruleDeps: Expr[Seq[ProjectDep]] = SbtBazelLogic.ruleDeps.value
    val ruleRuntimeDeps: Expr[Seq[ProjectDep]] = SbtBazelLogic.ruleRuntimeDeps.value
    val ruleExports: Expr[Seq[ProjectDep]] = SbtBazelLogic.ruleExports.value
    val mavenDeps: Expr[Seq[ProjectDep]] = SbtBazelLogic.mavenDeps.value

    val project = Keys.thisProject.value
    val config = Compile
    val baseDirectory = Keys.baseDirectory.value
    val sources = (Keys.sources in Compile).value
    val mainClass = (Keys.mainClass in Compile).value
    val bazelScalaRulesVersion = SbtBazelKeys.bazelScalaRulesVersion.value
    val mavenRepos = (SbtBazelKeys.allMavenResolvers in Compile).value

    val srcs = sources.map { src =>
      relativize(baseDirectory.getAbsolutePath, src.getAbsolutePath)
    }.toList
    val projectName = nameFromString(project.id, config)
    val buildFile = baseDirectory / s"BUILD"
    val workspaceFile = baseDirectory / "WORKSPACE"

    logger.info(s"Generating files for: $projectName")

    def evaluate(expr: Expr[Seq[ProjectDep]]): List[String] = {
      logger.info(s"expr: ${expr.map(_.toString).show}")
      val moduleExpr: Expr[Set[ProjectDep]] =
        expr.map(_.toSet)
      logger.info(s"modules: ${moduleExpr.map(_.toString).show}")
      // FIXME: Not all deps are external. Currently fixed by only including ExternalDeps in `SbtBazelKeys.bazelRuleDeps`
      val labelExpr: Expr[Set[String]] =
        moduleExpr.map(_.map{
          case ProjectDep.ModuleIdDep(module) => s"//external:${normalizeBindName(module)}"
          case ProjectDep.ManualDep(dep) => dep
        })
      logger.info(s"labels: ${labelExpr.map(_.toString).show}")
      labelExpr(ExprF.evalAlgebra).toList.sorted
    }

    val scalaLibrary = BazelAst.Helpers.scalaLibrary(
      projectName,
      evaluate(ruleDeps), // todo: remove separate projectDependencies calculation
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
      val mavenModuleIds: Set[ModuleID] = mavenDeps.map(_.toSet)(ExprF.evalAlgebra).collect{case ProjectDep.ModuleIdDep(m) => m}

      val externalDepsAst: List[PyExpr] = for {
        moduleId    <- mavenModuleIds.toList
        mavenPyExpr <- mavenJar(moduleId, mavenRepos.toList, Keys.scalaVersion.value)
      } yield mavenPyExpr

      val workspaceAst = SbtBazelKeys.bazelCustomWorkspace.value.apply(
        BazelDsl.pyExprAlgebra(
          externalDepsAst,
          List.empty,
          bazelScalaRulesVersion
        )
      )

      val workspaceDoc = BazelAst.Render.renderPyExprs(workspaceAst.toList)
      val workspaceRendered = workspaceDoc.renderTrim(0)

      Files.write(workspaceFile.toPath, workspaceRendered.getBytes(DefaultCharset))
    }

    /* Render and write the BUILD file. */
    if (SbtBazelKeys.bazelBuildGenerate.value) {
      val buildAst = SbtBazelKeys.bazelCustomBuild.value.apply(
        BazelDsl.pyExprAlgebra(
          List.empty,
          List(scalaLibrary) ++ scalaBinary.fold[List[PyExpr]](List())(b => List(b)),
          bazelScalaRulesVersion
        )
      )
      val buildDoc = BazelAst.Render.renderPyExprs(buildAst.toList)
      val buildRendered = buildDoc.renderTrim(0)
      Files.write(buildFile.toPath, buildRendered.getBytes(DefaultCharset))
    }

    Some(buildFile)
  }
}
