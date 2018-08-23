addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.1")
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.9.0")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.1.2")
addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.9")

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value
