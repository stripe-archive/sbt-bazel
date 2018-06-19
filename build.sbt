import sbt.ScriptedPlugin.{autoImport => ScriptedKeys}

lazy val root = (project in file("."))
  .aggregate(plugin)

lazy val commonSettings = Seq(
  organization := "com.stripe",
  scalaVersion := "2.12.4",
  version      := "0.0.1-SNAPSHOT"
)

lazy val plugin = project
  .in(file("plugin"))
  .enablePlugins(ScriptedPlugin)
  .settings(
    ScriptedKeys.scriptedBufferLog := false,
    ScriptedKeys.scriptedLaunchOpts := {
      ScriptedKeys.scriptedLaunchOpts.value ++
      Seq("-Xmx1024M", "-Dplugin.version=" + Keys.version.value)
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

lazy val deps = new {
  val cats = "org.typelevel" %% "cats-core" % "1.1.0"
  val catsEffect = "org.typelevel" %% "cats-effect" % "0.10"
  val paiges = "org.typelevel" %% "paiges-core" % "0.2.1"
  val scalacheck = "org.scalacheck" %% "scalacheck" % "1.13.4"
}
