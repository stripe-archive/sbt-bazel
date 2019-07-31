scalaVersion := "2.12.4"

ThisBuild / bazelScalaRulesVersion := "63eab9f4d80612e918ba954211f377cc83d27a07"

val root = (project in file("."))
  .settings(
    name := "root",
    bazelWorkspaceGenerate := true,
  )
