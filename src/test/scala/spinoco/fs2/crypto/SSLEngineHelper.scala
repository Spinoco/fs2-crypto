package spinoco.fs2.crypto

import java.security.KeyStore
import javax.net.ssl.{KeyManagerFactory, SSLContext, SSLEngine, TrustManagerFactory}

import fs2.util.Async
import fs2.{Strategy, Task}

import scala.concurrent.ExecutionContext


object SSLEngineHelper {

  implicit lazy val S = Strategy.fromExecutionContext(ExecutionContext.Implicits.global)

  implicit lazy val F = implicitly[Async[Task]]

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
