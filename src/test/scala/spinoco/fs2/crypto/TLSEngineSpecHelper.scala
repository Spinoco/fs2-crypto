package spinoco.fs2.crypto

import java.nio.channels.AsynchronousChannelGroup
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.net.ssl.{KeyManagerFactory, SSLContext, SSLEngine, TrustManagerFactory}

import fs2._

import scala.concurrent.ExecutionContext


object TLSEngineSpecHelper {

  implicit val ec = ExecutionContext.Implicits.global
  val sslEc = ExecutionContext.Implicits.global
  implicit val Sch = Scheduler.fromScheduledExecutorService(Executors.newScheduledThreadPool(4))
  implicit val AG = AsynchronousChannelGroup.withThreadPool(Executors.newCachedThreadPool())

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
