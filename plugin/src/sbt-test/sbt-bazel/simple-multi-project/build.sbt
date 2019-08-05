scalaVersion := "2.12.4"

val A = (project in file("A"))
val B1 = (project in file("B1"))
  .dependsOn(A)
val B2 = (project in file("B2"))
  .dependsOn(A)
val C = (project in file("C"))
  .dependsOn(B1)
  .dependsOn(B2)
