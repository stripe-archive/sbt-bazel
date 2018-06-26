package com.stripe.sbt.bazel

import sbt._
import com.stripe.sbt.bazel.BazelAst._
import java.nio.charset.Charset
import java.nio.file.Files
import java.io.{File => JFile}

object SbtBazelPlugin extends AutoPlugin {

  import sbt.plugins.JvmPlugin

  override def requires = JvmPlugin

  override def trigger = allRequirements

  lazy val autoImport = SbtBazelKeys

  import SbtBazelKeys._

  lazy val baseSettings: Seq[Def.Setting[_]] = Seq(
    bazelGenerate := Def.taskDyn {
      if (!bazelSkip.value) {
        SbtBazel.bazelGenerateMain
      } else {
        Def.task[Option[File]](None)
      }
    }.value,
    bazelSkip := false,
    bazelMavenRepo := None,
    bazelWorkspaceGenerateFlag := false,
    bazelWorkspaceGenerate := SbtBazel.bazelWorkspaceGenerateMain.value
  )

  override def projectSettings: Seq[Def.Setting[_]] = baseSettings
}

object SbtBazelKeys {
  val bazelGenerate = sbt.taskKey[Option[File]]("Generate a Bazel build file for this project")

  val bazelWorkspaceGenerate = sbt.taskKey[File]("Generate a Bazel WORKSPACE file")

  val bazelSkip = sbt.settingKey[Boolean]("Skip bazel BUILD generation for this project")

  val bazelMavenRepo = sbt.settingKey[Option[String]]("URI of maven repository")

  val bazelWorkspaceGenerateFlag = sbt.settingKey[Boolean]("Generate a WORKSPACE file for this project and child projects.")
}

object SbtBazel {

  import org.typelevel.paiges._

  private final val DefaultCharset = Charset.defaultCharset

  private def nameFromString(name: String, configuration: Configuration): String =
    if (configuration == Compile) name else name + "-test"

  private def nameFromRef(
    ref: ProjectReference,
    projectBase: String
  ) = {
    val target = s"//$projectBase:${ref.project}"
    target
  }

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

  def source(s: Source): Doc = {
    s match {
      case Source.Path(p) => str(p)
      case Source.Glob(srcs) => Doc.intercalate(Doc.char(','), srcs.map(source))
    }
  }


  def scalaLibraryRender(ri: ScalaLibrary): Doc = {
    val deps = arr(
      ri.projectDeps.map(dep => str(dep)) ++
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

  def mavenJar(module: Attributed[File], repo: String): List[PyExpr] = {
    val Some(moduleId) = module.metadata.get(Keys.moduleID.key)

    val jarName = normalizeJarName(moduleId)
    val bindName = normalizeBindName(moduleId)
    val mvnCoords = mavenCoordinates(moduleId)

    val jar = PyCall("maven_jar",
      List(
        "name" -> PyStr(s"$jarName"),
        "artifact" -> PyStr(s"$mvnCoords"),
        "repository" -> PyStr(repo)
      )
    )

    val bind = PyCall("bind",
      List(
        "name" -> PyStr(s"$bindName"),
        "actual" -> PyStr(s"@$jarName//jar:file")
      )
    )

    List(jar, bind)
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
    if (args.length >= 2) {
      pyArgs(args).tightBracketBy(name + Doc.char('('), Doc.char(')'))
    } else if (args.length == 1) {
      Doc.text("name(") + pyArgs(args) + Doc.char(')')
    } else {
      name + Doc.text("()")
    }
  }


  def relativize(base: String, full: String): String = {
    new JFile(new File(base).getAbsolutePath).toURI.relativize(new File(full).toURI).getPath
  }

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
      case PyCall(name, args) =>
        val docArgs = args.map { case (k, v) =>
          Doc.text(k) -> renderPyExpr(v)
        }
        pyCall(Doc.text(name), docArgs)
    }
  }

  def renderPyExprs(exprs: List[PyExpr]): Doc = {
    Doc.intercalate(Doc.lineBreak, exprs.map(renderPyExpr))
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
        Source.Path(relativize(baseDirectory.getAbsolutePath, src.getAbsolutePath))
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
            val rel = relativize(basePath, projectBasePath)
            logger.info(s"$projectName $basePath $projectBasePath $rel")
            s"$rel:${dep.project.project}"
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

      val externalDeps = managedClasspath.flatMap(dep => mavenJar(dep, mavenRepoUrl)).toList

      val scalaLibrary = ScalaLibrary(name = projectName,
        projectDeps = projectDependencies,
        binaryDeps = binDeps,
        sources = srcs,
        visibility = Public
      )

      val scalaBinary = mainClass.map { m =>
        ScalaBinary(
          name = s"$projectName-bin",
          projectDeps = List(s":$projectName"),
          mainClass = m
        )
      }


      /* Render and write the workspace file */

      val workspaceAst = workspacePrelude("63eab9f4d80612e918ba954211f377cc83d27a07") ++ externalDeps
      val workspaceDoc = renderPyExprs(workspaceAst)
      val workspaceRendered = workspaceDoc.renderTrim(0)

      // Only generate the workspace for the root target.
      val generateWorkspace = Keys.executionRoots.value.contains(Keys.resolvedScoped.value)

      if (generateWorkspace) {
        Files.write(workspaceFile.toPath, workspaceRendered.getBytes(DefaultCharset))
      }

      /* Render and write the BUILD file. */
      val buildDoc = renderPyExprs(buildPrelude) + Doc.lineBreak +
        scalaLibraryRender(scalaLibrary) + Doc.lineBreak +
        scalaBinary.map(sb => scalaBinaryRender(sb)).getOrElse(Doc.empty)

      val buildRendered = buildDoc.renderTrim(0)

      Files.write(buildFile.toPath, buildRendered.getBytes(DefaultCharset))

      Some(buildFile)
    }
  }

  def bazelWorkspaceGenerateMain: Def.Initialize[Task[File]] = Def.taskDyn {



    Def.task(???)
  }

}
