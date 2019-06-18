import sbtrelease.Version

val ReleaseTag = """^release/([\d\.]+a?)$""".r

lazy val contributors = Seq(
  "pchlupacek" -> "Pavel Chlupáček"
  , "achlupacek" -> "Adam Chlupáček"
)

lazy val commonSettings = Seq(
  organization := "com.spinoco",
  scalacOptions ++= commonScalacOptions(scalaVersion.value),
  scalacOptions in (Compile, console) ~= {_.filterNot("-Ywarn-unused-import" == _).filterNot("-Xlint" == _).filterNot("-Xfatal-warnings" == _)},
  scalacOptions in (Compile, console) += "-Ydelambdafy:inline",
  scalacOptions in (Test, console) := (scalacOptions in (Compile, console)).value,
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.10.3"),
  libraryDependencies ++= Seq(
    "com.github.mpilquist" %% "simulacrum" % "0.19.0",
    "co.fs2" %% "fs2-core" % "1.1.0-M1",
    "co.fs2" %% "fs2-io" % "1.1.0-M1",
    "com.chuusai" %% "shapeless" % "2.3.3" % "test",
    "org.scalacheck" %%% "scalacheck" % "1.14.0" % "test"
  ) ++ macroDependencies(scalaVersion.value),
  scmInfo := Some(ScmInfo(url("https://github.com/Spinoco/fs2-crypto"), "git@github.com:Spinoco/fs2-crypto.git")),
  homepage := Some(url("https://https://github.com/Spinoco/fs2-crypto")),
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  initialCommands := s"""
    import fs2._
    import scala.concurrent.ExecutionContext.Implicits.global
  """,
)  ++ testSettings ++ scaladocSettings ++ publishingSettings ++ releaseSettings

lazy val testSettings = Seq(
  parallelExecution in Test := false,
  testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-oDF"),
  publishArtifact in Test := true
)

def macroDependencies(scalaVersion: String) = 
  CrossVersion.partialVersion(scalaVersion) match {
    case Some((2, minor)) if minor < 13 =>
      Seq(
        compilerPlugin(("org.scalamacros" %% "paradise" % "2.1.1").cross(CrossVersion.patch))
      )
    case _ => Seq()
  }

def commonScalacOptions(scalaVersion: String) =
  Seq(
    "-encoding",
    "UTF-8",
    "-feature",
    "-language:existentials",
    "-language:higherKinds",
    "-language:implicitConversions",
    "-language:experimental.macros",
    "-language:postfixOps",
    "-unchecked",
    "-Ywarn-dead-code",
    "-Ywarn-numeric-widen",
    "-Ywarn-value-discard",
    "-Xfuture"
  ) ++ (if (priorTo2_13(scalaVersion))
          Seq(
            "-Yno-adapted-args",
            "-Xfatal-warnings", // TODO: add the following two back to 2.13 (deprecation?)
            "-deprecation"
          )
        else
          Seq(
            "-Ymacro-annotations"
          ))

def priorTo2_13(scalaVersion: String): Boolean =
  CrossVersion.partialVersion(scalaVersion) match {
    case Some((2, minor)) if minor < 13 => true
    case _                              => false
}

def scmBranch(v: String): String = {
  val Some(ver) = Version(v)
  if(ver.qualifier.exists(_ == "-SNAPSHOT"))
    // support branch (0.9.0-SNAPSHOT -> series/0.9)
    s"series/${ver.copy(subversions = ver.subversions.take(1), qualifier = None).string}"
  else
    // release tag (0.9.0-M2 -> v0.9.0-M2)
    s"v${ver.string}"
}

lazy val scaladocSettings = Seq(
  scalacOptions in (Compile, doc) ++= Seq(
    "-doc-source-url", s"${scmInfo.value.get.browseUrl}/tree/${scmBranch(version.value)}€{FILE_PATH}.scala",
    "-sourcepath", baseDirectory.in(LocalRootProject).value.getAbsolutePath,
    "-implicits",
    "-implicits-sound-shadowing",
    "-implicits-show-all"
  ),
  scalacOptions in (Compile, doc) ~= { _ filterNot { _ == "-Xfatal-warnings" } },
  autoAPIMappings := true
)

lazy val publishingSettings = Seq(
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (version.value.trim.endsWith("SNAPSHOT"))
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
  credentials ++= (for {
    username <- Option(System.getenv().get("SONATYPE_USERNAME"))
    password <- Option(System.getenv().get("SONATYPE_PASSWORD"))
  } yield Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", username, password)).toSeq,
  publishMavenStyle := true,
  pomIncludeRepository := { _ => false },
  pomExtra := {
    <developers>
      {for ((username, name) <- contributors) yield
      <developer>
        <id>{username}</id>
        <name>{name}</name>
        <url>http://github.com/{username}</url>
      </developer>
      }
    </developers>
  },
  pomPostProcess := { node =>
    import scala.xml._
    import scala.xml.transform._
    def stripIf(f: Node => Boolean) = new RewriteRule {
      override def transform(n: Node) =
        if (f(n)) NodeSeq.Empty else n
    }
    val stripTestScope = stripIf { n => n.label == "dependency" && (n \ "scope").text == "test" }
    new RuleTransformer(stripTestScope).transform(node)(0)
  }
)

lazy val commonJsSettings = Seq(
  scalaJSOptimizerOptions ~= { options =>
    // https://github.com/scala-js/scala-js/issues/2798
    try {
      scala.util.Properties.isJavaAtLeast("1.8")
      options
    } catch {
      case _: NumberFormatException =>
        options.withParallel(false)
    }
  },
  scalaJSStage in Test := FastOptStage,
  jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(),
  scalacOptions in Compile += {
    val dir = project.base.toURI.toString.replaceFirst("[^/]+/?$", "")
    val url = "https://raw.githubusercontent.com/Spinoco/fs2-crypto"
    s"-P:scalajs:mapSourceURI:$dir->$url/${scmBranch(version.value)}/"
  }
)

lazy val noPublish = {
  import com.typesafe.sbt.pgp.PgpKeys.publishSigned
  Seq(
    publish := {},
    publishLocal := {},
    publishSigned := {},
    publishArtifact := false
  )
}

lazy val releaseSettings = Seq(
  releaseCrossBuild := true,
  releasePublishArtifactsAction := PgpKeys.publishSigned.value
)


def previousVersion(currentVersion: String): Option[String] = {
  val Version = """(\d+)\.(\d+)\.(\d+).*""".r
  val Version(x, y, z) = currentVersion
  if (z == "0") None
  else Some(s"$x.$y.${z.toInt - 1}")
}



lazy val `fs2-crypto` = project.in(file(".")).
  settings(commonSettings: _*).
  settings(
    name := "fs2-crypto"
  )

