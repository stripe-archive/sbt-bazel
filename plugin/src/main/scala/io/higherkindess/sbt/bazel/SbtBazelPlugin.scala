package io.higherkindness

import sbt._

import java.nio.charset.Charset
import java.nio.file.Files

object SbtBazelPlugin extends AutoPlugin {
  import sbt.plugins.JvmPlugin
  override def requires = JvmPlugin
  override def trigger = allRequirements

  def autoImport = SbtBazelkeys

  override def projectSettings: Seq[Def.Setting[_]] = {
    val settings = List(
      SbtBazelkeys.bazelGenerate := SbtBazel.bazelGenerate.value)
    settings.flatMap(ss => sbt.inConfig(Compile)(ss) ++ sbt.inConfig(Test)(ss))
  }
}

object SbtBazelkeys {
  val bazelGenerate: sbt.TaskKey[File] =
    sbt.taskKey[File]("Generate a Bazel build file for this project")
}

final case class RuleInvocation(
  name: String,
  projectDeps: List[String],
  binaryDeps: List[String],
  sources: List[Source]
)

sealed trait Source
object Source {
  final case class Path(value: String) extends Source
  final case class Glob(sources: List[Source]) extends Source
}

object SbtBazel {
  private final val DefaultCharset = Charset.defaultCharset

  private def nameFromString(name: String, configuration: Configuration): String =
    if (configuration == Compile) name else name + "-test"

  private def nameFromRef(ref: ProjectRef, configuration: Configuration): String =
    nameFromString(ref.project, configuration)

  import org.typelevel.paiges._

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

  def render(ri: RuleInvocation): Doc = {

    def arr(entries: List[Doc]): Doc =
      join(entries).tightBracketBy(Doc.char('['), Doc.char(']'))

    val deps = arr(
      ri.projectDeps.map(l => str(s":$l")))

    val fields = List(
      field("name", str(ri.name)),
      field("deps", deps))

    join(fields).tightBracketBy(
      Doc.text("scala_library") + Doc.char('('),
      Doc.char(')'))
  }


  lazy val bazelGenerate: Def.Initialize[Task[File]] = Def.task {
    val logger = Keys.streams.value.log
    val project = Keys.thisProject.value
    val configuration = Keys.configuration.value
    val baseDirectory = Keys.baseDirectory.value

    val projectName = nameFromString(project.id, configuration)
    val buildFile = baseDirectory / s"BUILD"

    val baseProjectDependency = if (configuration == Test) List(project.id) else Nil

    val projectDependencies = project.dependencies
      .map(dep => nameFromRef(dep.project, configuration))
      .toList

    val ri = RuleInvocation(
      name = projectName,
      projectDeps = projectDependencies,
      binaryDeps = Nil,
      sources = Nil)

    val rendered: String =
      render(ri).render(80)
    logger.info(">> " + rendered)

    val contents = "rendered"
    Files.write(buildFile.toPath, contents.getBytes(DefaultCharset))

    buildFile
  }
}
