package spinoco.fs2.crypto

import java.nio.channels.AsynchronousChannelGroup
import java.security.KeyStore
import java.util.concurrent.Executors

import cats.effect.{Concurrent, IO, Timer}
import javax.net.ssl.{KeyManagerFactory, SSLContext, SSLEngine, TrustManagerFactory}

import scala.concurrent.ExecutionContext


object TLSEngineSpecHelper {

  val sslEc = ExecutionContext.Implicits.global
  implicit val AG = AsynchronousChannelGroup.withThreadPool(Executors.newCachedThreadPool())
  implicit val _timer: Timer[IO] = IO.timer(ExecutionContext.Implicits.global)
  implicit val _concurrent: Concurrent[IO] = IO.ioConcurrentEffect(_timer)

  lazy val sslCtx = {
    val keyStore = KeyStore.getInstance("jks")
    val keyStoreFile = getClass.getResourceAsStream("/mykeystore.jks")
    keyStore.load(keyStoreFile, "password".toCharArray )
    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(keyStore, "pass".toCharArray)
    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trustManagerFactory.init(keyStore)

    val ctx = SSLContext.getInstance("TLS")
    ctx.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, null)
    ctx
  }

  def clientEngine: SSLEngine = {
    val engine = sslCtx.createSSLEngine()
    engine.setUseClientMode(true)
    engine.setNeedClientAuth(false)
    engine
  }

  def serverEngine: SSLEngine = {
    val engine = sslCtx.createSSLEngine()
    engine.setUseClientMode(false)
    engine.setNeedClientAuth(false)
    engine
  }

}
