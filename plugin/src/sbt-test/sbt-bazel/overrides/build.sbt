import sbt.Keys.resolvers

scalaVersion := "2.12.4"

ThisBuild / bazelScalaRulesVersion := "63eab9f4d80612e918ba954211f377cc83d27a07"

lazy val hello = (project in file("."))
  .settings(
    name := "Hello",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % "0.9.3",
      "io.circe" %% "circe-generic" % "0.9.3",
    ),
    resolvers += MavenRepository(s"example", s"https://example.com"),
    bazelWorkspaceGenerate := true,
    bazelCustomWorkspace := BazelString("# Custom workspace") +: WorkspacePrelude +: MavenBindings,
    bazelCustomBuild := BazelString ("# Custom build") +: BuildPrelude +: BuildTargets,
    bazelMavenDeps := AllExternalDeps(Compile) + ModuleDep("io.circe" %% "circe-parser" % "0.9.3"),
  )
