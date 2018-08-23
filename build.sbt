import sbt.ScriptedPlugin.{autoImport => ScriptedKeys}

lazy val root = (project in file("."))
  .aggregate(plugin)

lazy val commonSettings = Seq(
  organization := "com.stripe",
  scalaVersion := "2.12.4",
  version      := "0.0.1"
)

lazy val plugin = project
  .in(file("plugin"))
  .enablePlugins(ScriptedPlugin)
  .settings(
    ScriptedKeys.scriptedBufferLog := false,
    ScriptedKeys.scriptedLaunchOpts := {
      ScriptedKeys.scriptedLaunchOpts.value ++
      Seq("-Xmx2048M", "-Dplugin.version=" + Keys.version.value)
    })
  .settings(commonSettings)
  .settings(name := "sbt-bazel")
  .settings(sbtPlugin := true)
  .settings(libraryDependencies ++= Seq(
    deps.cats,
    deps.catsEffect,
    deps.paiges,
    deps.scalacheck % Test
  ))
  .settings(
    releasePublishArtifactsAction := PgpKeys.publishSigned.value,
    useGpg := true,
    pomIncludeRepository := { _ => false },
    publishMavenStyle := true,

    licenses := Seq("Apache 2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),

    homepage := Some(url("https://github.com/stripe/sbt-bazel")),

    scmInfo := Some(
      ScmInfo(
        url("https://github.com/stripe/sbt-bazel"),
        "scm:git@github.com:stripe/sbt-bazel.git"
      )
    ),

    developers := List(
      Developer(
        "beala-stripe",
        "Alex Beal",
        "beala@stripe.com",
        url("https://twitter.com/beala")
      ),
      Developer(
        "andyscott",
        "Andy Scott",
        "andyscott@stripe.com",
        url("https://twitter.com/andygscott")
      )
    ),

    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value)
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases"  at nexus + "service/local/staging/deploy/maven2")
    }
  )

lazy val deps = new {
  val cats = "org.typelevel" %% "cats-core" % "1.1.0"
  val catsEffect = "org.typelevel" %% "cats-effect" % "0.10"
  val paiges = "org.typelevel" %% "paiges-core" % "0.2.1"
  val scalacheck = "org.scalacheck" %% "scalacheck" % "1.13.4"
}
