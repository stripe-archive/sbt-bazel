package com.stripe.sbt.bazel

import _root_.io.circe._
import sbt._

// Rename sbt.io so it doesn't conflict with io.circe
//import sbt._
import java.nio.charset.Charset
import java.nio.file.Files
import java.io.{File => JFile}


object SbtBazelPlugin extends AutoPlugin {

  import sbt.plugins.JvmPlugin

  override def requires = JvmPlugin

  override def trigger = allRequirements

  def autoImport = SbtBazelkeys

  override def projectSettings: Seq[Def.Setting[_]] = {
    val settings = List(
      SbtBazelkeys.bazelGenerate := SbtBazel.bazelGenerate.value
    )

    settings.flatMap(ss => sbt.inConfig(Compile)(ss) ++ sbt.inConfig(Test)(ss))
  }
}

object SbtBazelkeys {
  val bazelGenerate: sbt.TaskKey[File] =
    sbt.taskKey[File]("Generate a Bazel build file for this project")
}

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

object SbtBazel {

  import org.typelevel.paiges._

  private final val DefaultCharset = Charset.defaultCharset

  val WorkspacePrelude =
    """
      |rules_scala_version="63eab9f4d80612e918ba954211f377cc83d27a07"
      |
      |http_archive(
      |	name = "io_bazel_rules_scala",
      |	url = "https://github.com/bazelbuild/rules_scala/archive/%s.zip"%rules_scala_version,
      |	type = "zip",
      |	strip_prefix = "rules_scala-%s" % rules_scala_version
      |	)
      |
      |load("@io_bazel_rules_scala//scala:scala.bzl", "scala_repositories")
      |scala_repositories()
      |
      |load("@io_bazel_rules_scala//scala:toolchains.bzl", "scala_register_toolchains")
      |scala_register_toolchains()
    """.stripMargin

  val BuildPredule = """load("@io_bazel_rules_scala//scala:scala.bzl", "scala_binary", "scala_library", "scala_test")"""

  private def nameFromString(name: String, configuration: Configuration): String =
    if (configuration == Compile) name else name + "-test"

  private def nameFromRef(ref: ProjectRef, configuration: Configuration): String =
    nameFromString(ref.project, configuration)

  private def nameFromLibDep(dep: ModuleID): String =
    s"${dep.organization.replace(".", "/")}/${dep.name}"

  private def mavenCoordinates(moduleId: ModuleID): String =
    s"${moduleId.organization}:${moduleId.name}:${moduleId.revision}"

  def join(docs: List[Doc]): Doc =
    Doc.intercalate(Doc.line, docs.map(_ + Doc.char(',')))

  def field(lhs: Doc, rhs: Doc): Doc =
    lhs + Doc.space + Doc.char('=') + Doc.space + rhs

  def field(name: String, rhs: Doc): Doc =
    field(Doc.text(name), rhs)

  def str(value: String): Doc = {
    val escaped = value.replaceAll("'", "\\'")
    Doc.text(s"'$escaped'")
  }

  def arr(entries: List[Doc]): Doc =
    if (entries.isEmpty) Doc.text("[]")
    else join(entries).tightBracketBy(Doc.char('['), Doc.char(']'))

  def vis(v: Visibility): Doc = {
    v match {
      case Public => Doc.text("\'//visibility:public\'")
      case Private => Doc.text("\'//visibility:private\'")
    }
  }

  def dependencies(ds: Dependencies): Json = {
    Json.fromFields(List("dependencies" -> Json.fromFields(ds.ds.mapValues(dependency))))
  }

  def dependency(d: Dependency): Json = {
    Json.fromFields(d.packages.mapValues(packageProps).toList)
  }

  def packageProps(p: PackageProps): Json = {
    Json.fromFields(List(
      "version" -> Json.fromString(p.version),
      "modules" -> Json.fromValues(p.modules.map(Json.fromString)),
      "exports" -> Json.fromValues(p.exports.map(Json.fromString)),
      "lang" -> Json.fromString(p.lang)
    )
    )
  }

  def source(s: Source): Doc = {
    s match {
      case Source.Path(p) => str(p)
      case Source.Glob(srcs) => Doc.intercalate(Doc.char(','), srcs.map(source))
    }
  }


  def scalaLibraryRender(ri: ScalaLibrary): Doc = {
    val deps = arr(
      ri.projectDeps.map(dep => str(s":$dep")) ++
        ri.binaryDeps.map(dep => str(dep))
    )

    val fields = List(
      field("name", str(ri.name)),
      field("deps", deps),
      field("visibility", arr(List(vis(ri.visibility)))),
      field("srcs", arr(ri.sources.map(source)))
    )

    join(fields).tightBracketBy(
      Doc.text("scala_library") + Doc.char('('),
      Doc.char(')')
    )
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

  def mavenJar(module: Attributed[File]): Doc = {
    val Some(moduleId) = module.metadata.get(Keys.moduleID.key)

    val jarName = normalizeJarName(moduleId)
    val bindName = normalizeBindName(moduleId)
    val mvnCoords = mavenCoordinates(moduleId)

    val mvnJar = pyCall(
      Doc.text("maven_jar"),
      List(
        Doc.text("name") -> str(s"$jarName"),
        Doc.text("artifact") -> str(s"$mvnCoords"),
        Doc.text("repository") -> str("https://nexus-content.northwest.corp.stripe.com:446/groups/public")
      )
    )

    val bind = pyCall(Doc.text("bind"), List(
      Doc.text("name") -> str(s"$bindName"),
      Doc.text("actual") -> str(s"@$jarName//jar:file")
    )
    )

    mvnJar + Doc.lineBreak + bind
  }

  def scalaBinaryRender(bin: ScalaBinary): Doc = {

    val deps = arr(bin.projectDeps.map(str))

    pyCall(
      Doc.text("scala_binary"),
      List(
        Doc.text("name") -> str(bin.name),
        Doc.text("deps") -> deps,
        Doc.text("main_class") -> str(bin.mainClass)
      )
    )
  }

  def pyArg(name: Doc, value: Doc): Doc = {
    name + Doc.space + Doc.char('=') + Doc.space + value
  }

  def pyArgs(args: List[(Doc, Doc)]): Doc = {
    Doc.intercalate(Doc.char(',') + Doc.lineOrSpace, args.map { case (name, value) => pyArg(name, value) })
  }

  def pyCall(name: Doc, args: List[(Doc, Doc)]): Doc = {
    pyArgs(args).tightBracketBy(name + Doc.char('('), Doc.char(')'))
  }


  def relativize(base: String, full: String): String = {
    new JFile(new File(base).getAbsolutePath).toURI.relativize(new File(full).toURI).getPath
  }

  lazy val bazelGenerate: Def.Initialize[Task[File]] = Def.task {
    val logger = Keys.streams.value.log
    val project = Keys.thisProject.value
    val configuration = Keys.configuration.value
    val baseDirectory = Keys.baseDirectory.value
    val libraryDependencies = Keys.libraryDependencies.value
    val classpath = Keys.externalDependencyClasspath.value
    val sources = Keys.sources.value
    val mainClass = Keys.mainClass.value

    val srcs = sources.map { src =>
      Source.Path(relativize(baseDirectory.getAbsolutePath, src.getAbsolutePath))
    }.toList

    val projectName = nameFromString(project.id, configuration)
    val buildFile = baseDirectory / s"BUILD"
    val workspaceFile = baseDirectory / "WORKSPACE"

    val baseProjectDependency = if (configuration == Test) List(project.id) else Nil

    val projectDependencies = project.dependencies
      .map(dep => nameFromRef(dep.project, configuration))
      .toList

    val binDeps = classpath
      .map(_.get(Keys.moduleID.key).get)
      .map { module =>
        val bindName = normalizeBindName(module)

        s"//external:$bindName"
      }.toList

    val externalDeps = Doc.intercalate(Doc.line, classpath.map(mavenJar))

    // TODO Create a workspace file

    val scalaLibrary = ScalaLibrary(name = projectName,
      projectDeps = projectDependencies,
      binaryDeps = binDeps,
      sources = srcs,
      visibility = Public
    )

    mainClass.map { main =>
      val scalaBinary = ScalaBinary(
        name = s"$projectName-bin",
        projectDeps = List(s":$projectName"),
        mainClass = main
      )
    }

    val workspaceRendered = WorkspacePrelude + '\n' +
      externalDeps.renderTrim(0)

    val rendered =
      BuildPredule + "\n" + scalaLibraryRender(scalaLibrary).renderTrim(0) + "\n" + scalaBinaryRender(scalaBinary).renderTrim(0)

    logger.info(s"BUILD:\n$rendered")

    Files.write(workspaceFile.toPath, workspaceRendered.getBytes(DefaultCharset))
    Files.write(buildFile.toPath, rendered.getBytes(DefaultCharset))

    buildFile
  }
}
