package spinoco.fs2.crypto.internal

import javax.net.ssl.SSLEngine

import fs2.Strategy
import fs2.util.Async
import fs2.util.syntax._

private[crypto] trait SSLTaskRunner[F[_]] {
  def runTasks: F[Unit]
}


private[crypto] object SSLTaskRunner {

  def mk[F[_]](engine: SSLEngine)(implicit F: Async[F], S: Strategy): F[SSLTaskRunner[F]] = F.delay {

    new SSLTaskRunner[F] {
      def runTasks: F[Unit] = F.delay { Option(engine.getDelegatedTask) } flatMap {
        case None => F.pure(())
        case Some(engineTask) =>
          F.async[Unit] { cb =>
            F.delay { S {
              try { engineTask.run(); cb(Right(())) }
              catch { case t : Throwable => cb(Left(t))}
            }}
          } flatMap { _ => runTasks }
      }
    }
  }

}
