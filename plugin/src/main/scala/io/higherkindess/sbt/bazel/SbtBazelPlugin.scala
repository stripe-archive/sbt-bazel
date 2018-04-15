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


object SbtBazel {
  private final val DefaultCharset = Charset.defaultCharset

  lazy val bazelGenerate: Def.Initialize[Task[File]] = Def.task {
    val logger = Keys.streams.value.log
    val project = Keys.thisProject.value
    val configuration = Keys.configuration.value
    val baseDirectory = Keys.baseDirectory.value

    def nameFromString(name: String, configuration: Configuration): String =
      if (configuration == Compile) name else name + "-test"

    val projectName = nameFromString(project.id, configuration)
    val buildFile = baseDirectory / s"BUILD"

    val contents = "# todo"
    Files.write(buildFile.toPath, contents.getBytes(DefaultCharset))

    buildFile
  }
}
