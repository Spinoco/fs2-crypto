package spinoco.fs2.crypto

import java.nio.channels.AsynchronousChannelGroup
import java.security.KeyStore
import java.util.concurrent.Executors

import cats.effect.{Blocker, Concurrent, ContextShift, IO, Timer}
import javax.net.ssl.{KeyManagerFactory, SSLContext, SSLEngine, TrustManagerFactory}

import scala.concurrent.ExecutionContext


object TLSEngineSpecHelper {

  implicit lazy val _concurrent: Concurrent[IO] = IO.ioConcurrentEffect(_cs)
  lazy val sslEc = ExecutionContext.Implicits.global
  lazy val AG = AsynchronousChannelGroup.withThreadPool(Executors.newCachedThreadPool())
  lazy val blocker = Blocker.liftExecutionContext(ExecutionContext.global)
  lazy val SG = fs2.io.tcp.SocketGroup(blocker)
  implicit lazy val _cs: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  implicit lazy val _timer: Timer[IO] = IO.timer(ExecutionContext.Implicits.global)
  

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
