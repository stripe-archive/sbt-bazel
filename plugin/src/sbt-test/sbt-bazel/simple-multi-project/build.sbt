scalaVersion := "2.12.4"

ThisBuild / bazelScalaRulesVersion := "63eab9f4d80612e918ba954211f377cc83d27a07"

val A = (project in file("A"))
val B1 = (project in file("B1"))
  .dependsOn(A)
val B2 = (project in file("B2"))
  .dependsOn(A)
val C = (project in file("C"))
  .dependsOn(B1)
  .dependsOn(B2)
