package com.stripe.sbt.bazel

import sbt._
import com.stripe.sbt.bazel.BazelAst._
import java.nio.charset.Charset
import java.nio.file.Files
import java.io.{File => JFile}

import com.stripe.sbt.bazel.SbtBazelKeys.bazelWorkspaceGenerate

object SbtBazelPlugin extends AutoPlugin {

  import sbt.plugins.JvmPlugin

  override def requires = JvmPlugin

  override def trigger = allRequirements

  lazy val autoImport = SbtBazelKeys

  import SbtBazelKeys._

  lazy val baseSettings: Seq[Def.Setting[_]] = Seq(
    bazelGenerate := SbtBazel.bazelGenerateMain.value,
    bazelBuildGenerate := true,
    bazelMavenRepo := None,
    (SbtBazelKeys.bazelMavenRepo in ThisBuild) := None,
    bazelWorkspaceGenerate := false,
  )

  override def projectSettings: Seq[Def.Setting[_]] = baseSettings
}

object SbtBazelKeys {
  val bazelGenerate = sbt.taskKey[Option[File]]("Generate a Bazel build file for this project")

  val bazelBuildGenerate = sbt.settingKey[Boolean]("Generate BUILD for this project")

  val bazelWorkspaceGenerate = sbt.settingKey[Boolean]("Generate a WORKSPACE file for this project and child projects.")

  val bazelMavenRepo = sbt.settingKey[Option[String]]("URI of maven repository")

  val bazelScalaRulesVersion = sbt.settingKey[String]("SHA of scala-rules version")
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

  lazy val bazelGenerateMain: Def.Initialize[Task[Option[File]]] = Def.taskDyn {

    Def.task {
      val logger = Keys.streams.value.log
      val project = Keys.thisProject.value
      val configuration = Compile
      val baseDirectory = Keys.baseDirectory.value
      val managedClasspath = (Keys.managedClasspath in Compile).value
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

      val binDeps = managedClasspath
        .map(_.get(Keys.moduleID.key).get)
        .map { module =>
          val bindName = normalizeBindName(module)

          s"//external:$bindName"
        }.toList

      val mavenRepoUrl = SbtBazelKeys.bazelMavenRepo.value
        .orElse((SbtBazelKeys.bazelMavenRepo in ThisBuild).value)
        .getOrElse(sbt.Resolver.DefaultMavenRepositoryRoot)

      val scalaLibrary = BazelAst.Helpers.scalaLibrary(
        projectName,
        projectDependencies ++ binDeps,
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
      if(bazelWorkspaceGenerate.value) {
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
      if(SbtBazelKeys.bazelBuildGenerate.value) {
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
}
