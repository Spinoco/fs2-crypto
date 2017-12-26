package spinoco.fs2.crypto.internal

import javax.net.ssl.SSLEngine

import cats.effect.Effect
import cats.syntax.all._

import scala.concurrent.ExecutionContext

private[crypto] trait SSLTaskRunner[F[_]] {
  def runTasks: F[Unit]
}


private[crypto] object SSLTaskRunner {

  def mk[F[_]](engine: SSLEngine, sslEc: ExecutionContext)(implicit F: Effect[F], ec: ExecutionContext): F[SSLTaskRunner[F]] = F.delay {

    new SSLTaskRunner[F] {
      def runTasks: F[Unit] = F.delay { Option(engine.getDelegatedTask) } flatMap {
        case None => F.shift >> F.pure(()) // shift to execution context from SSL one
        case Some(engineTask) =>
          F.async[Unit] { cb =>
            sslEc.execute (() => {
              try { engineTask.run(); cb(Right(())) }
              catch { case t : Throwable => cb(Left(t))}
            })
          } flatMap { _ => runTasks }
      }
    }
  }

}
