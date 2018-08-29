# sbt-bazel

[![Build Status](https://travis-ci.org/stripe/sbt-bazel.svg?branch=master)](https://travis-ci.org/stripe/sbt-bazel)

**This plugin is considered experimental. Expect frequent breaking changes.**

sbt-bazel is an sbt plugin that converts sbt projects to Bazel workspaces. This readme assumes you have basic knowledge of how a Bazel workspace is organized. 

This plugin will generate BUILD files for each project along with a WORKSPACE files that handles importing [rules_scala](https://github.com/bazelbuild/rules_scala) and downloading binary artifacts from Maven repos.

# Typical Usage

The [`readme-example`](https://github.com/stripe/sbt-bazel/tree/master/plugin/src/sbt-test/sbt-bazel/readme-example) project in the plugin's test directory is a minimal usage example. This section will walk through the settings used.

First you must install the plugin in your project. Do this by adding the following to your project's `project/plugins.sbt`:

```scala
addSbtPlugin("com.stripe" %% "sbt-bazel" % "0.0.1")
```

In your project's [`build.sbt`](https://github.com/stripe/sbt-bazel/tree/master/plugin/src/sbt-test/sbt-bazel/readme-example/build.sbt) file, you must now set the version of `rules_scala`. This is done by setting `bazelScalaRulesVersion` to the SHA of the `rules_scala` commit you'd like to use. For example, to set the version to [0eab80ff0696d419aa54c2ab4b847ce9bdcbb379](https://github.com/bazelbuild/rules_scala/commit/0eab80ff0696d419aa54c2ab4b847ce9bdcbb379) add the following to the top of your build file:

```scala
ThisBuild / bazelScalaRulesVersion := "0eab80ff0696d419aa54c2ab4b847ce9bdcbb379"
```

A common sbt pattern is to set up a root project that does not contain any source files itself, but is instead an aggregation all of a repo's sub-projects. This is the pattern the example project follows. 

In order to generate one `WORKSPACE` file, enable workspace file generation with `bazelWorkspaceGenerate` for the root project. Because the root project is just an empty aggregation, it also makes sense to turn off `BUILD` file generation with `bazelBuildGenerate`. Putting all this together, the root target will look as follows:

```scala
lazy val root =  project.
  in(file(".")).
  aggregate(core, example).
  settings(
    bazelWorkspaceGenerate := true,
    bazelBuildGenerate := false,
  )
```

You can now run the `bazelGenerate` task against the root project. The result will be `BUILD` files generated for all the aggregated projects. These will appear at the base directory of each of the projects. A `WORKSPACE` file will also be generated at the root directory of the root project, which will handle downloading binary dependencies and [rules_scala](https://github.com/bazelbuild/rules_scala).

See the `*.expect` files for examples of what the generated files will look like.

# Overriding Behavior

For more complicated projects, you may need to customize the behavior of the plugin. Several settings are available to you.

## Toggling File Generation

- `bazelWorkspaceGenerate`: When set to `true`, a `WORKSPACE` file is generated for the current project at the project's base directory. Otherwise, no file is generated. As shown above, this will typically be set to `true` for the root aggregate project.
- `bazelBuildGenerate`: When set to `false`, no `BUILD` file is generated for the current project. Otherwise, a `BUILD` file is generated for the current project at the project's base directory. As shown above, this will typically be set to `false` for the root aggregate project.

## Customizing Workspace and Build File Generation

- `bazelCustomWorkspace`: This allows customizing how the `WORKSPACE` file is generated using a DSL. By default, the `WORKSPACE` file is made up of two sections:
  - A `WorkspacePrelude`, which contains the code to load and set up `rules_scala`
  - And a `MavenBindings` section, which contains code to load and bind any dependency artifacts.

So the default setting is: `bazelCustomWorkspace := WorkspacePrelude +: MavenBindings`, where the `+:` operator concatenates sections together. 

The `BazelString` operand allows you add arbitrary text to the `WORKSPACE` file. The following would completely replace the generated `WORKSPACE` contents with the string passed to `BazelString`: `bazelCustomWorkspace := BazelString("# Custom workspace file")`

- `bazelCustomBuild`: This allows customizing how the `BUILD` file is generated using a DSL. By default, the `WORKSPACE` file is made up of two sections:
  - A `BuildPrelude`, which contains the code to load rules from `rules_scala`
  - And a `BuildTargets` section, which contains the generated `scala_library` target and `scala_binary` target, if applicable.
  
The `+:` operator concatenates sections and `BazelString` allows you to specify arbitrary strings.

## Customizing Dependency Generation

The plugin will add dependencies to targets based on sbt's `dependsOn` and `libraryDependencies` setting. If this needs to be overridden for some reason, this can be customized with the `bazelRuleDeps` setting. The default setting is `bazelRuleDeps := Deps(Compile)`, which means each target will include dependencies on any internal project dependencies and all dependencies in `Keys.externalDependencyClasspath`.

Each operand of the DSL specifies a set of dependencies, and these sets can be used in an expression with the normal set operators: `+`, `-`, `∪`, and `∩`.

The operands are:

- `Deps(config: Configuration)`: This denotes the set of all internal and external dependencies for a given config.
- `ModuleDep(moduleId: ModuleID)`: This denotes the dependency referred to by the given `ModuleId`. For example, `ModuleDep("io.circe" %% "circe-parser" % "0.9.3")`.
- `StringDep(dep: String)`: This denotes the dependency referred to by the given string. For example, `StringDep("//core")`.
- `BazelDep(path: String, name: String)`: This denotes the dependency referred to by the combination of the given path and target name. For example, `BazelDep("//core", "core")` denotes the Bazel dependency `//core:core`.
- `EmptyDep`: This denotes the empty set.

Putting this together, the following would remove the `circe-parser` 0.9.2 dependency and substitute it for version 0.9.3:

```scala
bazelRuleDeps := Deps(Compile) - 
  ModuleDep("io.circe" %% "circe-parser" % "0.9.2") + 
  ModuleDep("io.circe" %% "circe-parser" % "0.9.3")
```

## Customizing Maven Dependency Resolution in `WORKSPACE`

You can also customize which dependencies are loaded in the `WORKSPACE` file with `bazelMavenDeps`. The default is `bazelMavenDeps := AllExternalDeps(Compile)`

The operators and operands in the [Customizing Dependency Generation](#customizingdependencygeneration) section can be used here.

## scala_rules Version

- `bazelScalaRulesVersion`: Set the version of `scala_rules` to a specific SHA.

# Limitations

The plugin has the following limitations:

- All dependencies in the `Keys.externalDependencyClasspath` are added as compile time dependencies, even if they are only needed at runtime.
- Any additional resolvers added to the `Resolver` setting are used as mirrors in the `WORKSPACE` file for *all* dependencies. 
- Only Maven resolvers are supported.

# Contributing

Contributions are welcome. If you have a large contribution in mind, please open an issue to discuss the change first.

# Authors

- [Andy Scott](https://twitter.com/andygscott)
- [Alex Beal](https://twitter.com/beala)