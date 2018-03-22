package bloop.monix

import monix.eval.{Callback, Task}
import monix.execution.Ack.{Continue, Stop}
import monix.execution.{Ack, CancelableFuture, Scheduler}
import monix.execution.cancelables.AssignableCancelable
import monix.execution.misc.NonFatal
import monix.reactive.Consumer
import monix.reactive.observers.Subscriber

import scala.concurrent.Future

/** Implementation for [[monix.reactive.Consumer.foldLeftAsync]]. */
final class FoldLeftSyncConsumer[A, R](initial: () => R, f: (R, A) => Task[R])
    extends Consumer[A, R] {

  def createSubscriber(cb: Callback[R], s: Scheduler): (Subscriber[A], AssignableCancelable) = {
    val out = new Subscriber[A] {
      implicit val scheduler: Scheduler = s
      private[this] var isDone: Boolean = false
      private[this] var state: R = initial()
      private[this] var running: Option[CancelableFuture[Ack]] = None

      def onNext(elem: A): Future[Ack] = {
        // Do the `: Continue` and `: Stop` because of a bug in Scalameta semanticdb generation
        def task: Task[Ack] = f(state, elem).transform(update => {
          state = update
          Continue: Continue
        }, error => {
          onError(error)
          Stop: Stop
        })

        try {
          println(s"Consuming an event $elem in scheduler $s with ${s.executionModel}!")
          val future = running match {
            case Some(previous) => previous.flatMap(_ => task.runAsync)
            case None => task.runAsync
          }

          running = Some(future)
          future
        } catch {
          case NonFatal(ex) =>
            onError(ex)
            Stop
        }
      }

      def onComplete(): Unit = {
        if (!isDone) {
          isDone = true
          cb.onSuccess(state)
        }
      }

      def onError(ex: Throwable): Unit = {
        if (!isDone) {
          isDone = true
          cb.onError(ex)
        }
      }
    }

    (out, AssignableCancelable.dummy)
  }
}

object FoldLeftSyncConsumer {
  def consume[S,A](initial: => S)(f: (S,A) => Task[S]): Consumer[A,S] =
    new FoldLeftSyncConsumer[A,S](initial _, f)
}