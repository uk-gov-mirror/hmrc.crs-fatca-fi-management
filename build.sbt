import uk.gov.hmrc.DefaultBuildSettings

ThisBuild / majorVersion := 0
ThisBuild / scalaVersion := "2.13.16"

lazy val microservice = Project("crs-fatca-fi-management", file("."))
  .enablePlugins(PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin) //Required to prevent https://github.com/scalatest/scalatest/issues/1427
  .settings(
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test,
    PlayKeys.playDefaultPort := 10034,
    Compile / scalafmtOnCompile := true,
    Test / scalafmtOnCompile := true,
    ThisBuild / scalafmtOnCompile.withRank(KeyRanks.Invisible) := true,
    scalacOptions ++= Seq(
      "-Wconf:src=routes/.*:s",
      "-Wconf:src=.+/test/.+:s",
      "-Wconf:cat=deprecation&msg=\\.*()\\.*:s",
      "-Wconf:cat=unused-imports&site=<empty>:s",
      "-Wconf:cat=unused&src=.*RoutesPrefix\\.scala:s",
      "-Wconf:cat=unused&src=.*Routes\\.scala:s"
    )
  )
  .settings(inConfig(Test)(testSettings): _*)
  .settings(CodeCoverageSettings.settings: _*)

lazy val testSettings: Seq[Def.Setting[_]] = Seq(
  fork := true,
  unmanagedSourceDirectories += baseDirectory.value / "test-common")

lazy val it = project
  .enablePlugins(PlayScala)
  .dependsOn(microservice % "test->test")
  .settings(DefaultBuildSettings.itSettings())
  .settings(libraryDependencies ++= AppDependencies.itDependencies)
addCommandAlias(
  "precommit",
  "; clean ; scalafmtAll ; coverage ; test ; it/test ; coverageReport ; coverageOff"
)