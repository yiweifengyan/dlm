import $ivy.`com.goyeau::mill-scalafix::0.3.1`
import com.goyeau.mill.scalafix.ScalafixModule
import mill._, scalalib._, scalafmt._

val spinalVersion = "1.7.3"

trait CommonSpinalModule extends ScalaModule with ScalafmtModule with ScalafixModule {
  def scalaVersion = "2.11.12"
  def scalacOptions = Seq("-unchecked", "-deprecation", "-feature")

  def ivyDeps = Agg(
    ivy"com.github.spinalhdl::spinalhdl-core:$spinalVersion",
    ivy"com.github.spinalhdl::spinalhdl-lib:$spinalVersion",
    ivy"com.github.spinalhdl::spinalhdl-sim:$spinalVersion",
    ivy"com.lihaoyi::os-lib:0.8.0",
    ivy"org.scala-stm::scala-stm:0.11.0",
    ivy"org.scalactic::scalactic:3.2.18"
    )

  def scalacPluginIvyDeps = Agg(ivy"com.github.spinalhdl::spinalhdl-idsl-plugin:$spinalVersion")
  def scalafixIvyDeps = Agg(ivy"com.github.liancheng::organize-imports:0.6.0")
}

object dlm extends CommonSpinalModule {
  object test extends ScalaTests with TestModule.ScalaTest {
    def ivyDeps = Agg(ivy"org.scalactic::scalactic:3.2.17",
                      ivy"org.scalatest::scalatest:3.2.17:test")
    def testFramework = "org.scalatest.tools.Framework"
    def testSim(args: String*) = T.command {
      super.runMain("org.scalatest.run", args: _*)
    }
  }
}