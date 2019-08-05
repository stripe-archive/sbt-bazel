scalaVersion := "2.12.4"

val root = (project in file("."))
  .settings(
    name := "root",
    bazelWorkspaceGenerate := true,
  )
