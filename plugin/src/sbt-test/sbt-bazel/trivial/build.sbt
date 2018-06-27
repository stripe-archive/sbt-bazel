val root = (project in file("."))
  .settings(
    name := "root",
    bazelWorkspaceGenerate := true,
  )