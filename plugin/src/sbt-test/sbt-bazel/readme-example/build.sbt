scalaVersion := "2.12.4"

lazy val root =  project.
  in(file(".")).
  aggregate(core, example).
  settings(
    bazelWorkspaceGenerate := true,
    bazelBuildGenerate := false,
  )

lazy val core = (project in file("core")).
  settings(
    // These dependencies will be downloaded by the generated WORKSPACE file
    libraryDependencies += "org.typelevel" %% "cats-core" % "1.2.0",
    libraryDependencies += "org.typelevel" %% "cats-effect" % "1.0.0-RC2",
  )

lazy val example = (project in file("example")).
  dependsOn(core)
