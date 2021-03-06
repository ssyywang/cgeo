package cgeo.geocaching.utils;

import rx.Observable;
import rx.Observable.OnSubscribe;
import rx.Observable.Operator;
import rx.Scheduler;
import rx.Scheduler.Worker;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func0;
import rx.functions.Func1;
import rx.observables.BlockingObservable;
import rx.observers.Subscribers;
import rx.schedulers.Schedulers;
import rx.subjects.PublishSubject;
import rx.subscriptions.Subscriptions;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class RxUtils {

    private RxUtils() {
        // Utility class, not to be instantiated
    }

    public final static Scheduler computationScheduler = Schedulers.computation();

    public static final Scheduler networkScheduler = Schedulers.from(new ThreadPoolExecutor(10, 10, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>()));

    private static final HandlerThread looperCallbacksThread =
            new HandlerThread("Looper callbacks thread", Process.THREAD_PRIORITY_DEFAULT);

    static {
        looperCallbacksThread.start();
    }

    public static final Looper looperCallbacksLooper = looperCallbacksThread.getLooper();
    public static final Scheduler looperCallbacksScheduler = AndroidSchedulers.handlerThread(new Handler(looperCallbacksLooper));
    public static final Worker looperCallbacksWorker = looperCallbacksScheduler.createWorker();

    public static <T> void waitForCompletion(final BlockingObservable<T> observable) {
        observable.lastOrDefault(null);
    }

    public static void waitForCompletion(final Observable<?>... observables) {
        waitForCompletion(Observable.merge(observables).toBlocking());
    }

    /**
     * Subscribe function whose subscription and unsubscription take place on a looper thread.
     *
     * @param <T>
     *         the type of the observable
     */
    public static abstract class LooperCallbacks<T> implements OnSubscribe<T> {

        final AtomicInteger counter = new AtomicInteger(0);
        final long stopDelay;
        final TimeUnit stopDelayUnit;
        final protected PublishSubject<T> subject = PublishSubject.create();

        public LooperCallbacks(final long stopDelay, final TimeUnit stopDelayUnit) {
            this.stopDelay = stopDelay;
            this.stopDelayUnit = stopDelayUnit;
        }

        public LooperCallbacks() {
            this(0, TimeUnit.SECONDS);
        }

        @Override
        final public void call(final Subscriber<? super T> subscriber) {
            subscriber.add(subject.subscribe(Subscribers.from(subscriber)));
            looperCallbacksWorker.schedule(new Action0() {
                @Override
                public void call() {
                    if (counter.getAndIncrement() == 0) {
                        onStart();
                    }
                    subscriber.add(Subscriptions.create(new Action0() {
                        @Override
                        public void call() {
                            looperCallbacksWorker.schedule(new Action0() {
                                @Override
                                public void call() {
                                    if (counter.decrementAndGet() == 0) {
                                        onStop();
                                    }
                                }
                            }, stopDelay, stopDelayUnit);
                        }
                    }));
                }
            });
        }

        abstract protected void onStart();

        abstract protected void onStop();
    }

    public static <T> Operator<T, T> operatorTakeUntil(final Func1<? super T, Boolean> predicate) {
        return new Operator<T, T>() {
            @Override
            public Subscriber<? super T> call(final Subscriber<? super T> subscriber) {
                return new Subscriber<T>(subscriber) {
                    private boolean done = false;

                    @Override
                    public void onCompleted() {
                        if (!done) {
                            subscriber.onCompleted();
                        }
                    }

                    @Override
                    public void onError(final Throwable throwable) {
                        if (!done) {
                            subscriber.onError(throwable);
                        }
                    }

                    @Override
                    public void onNext(final T value) {
                        subscriber.onNext(value);
                        boolean shouldEnd = false;
                        try {
                            shouldEnd = predicate.call(value);
                        } catch (final Throwable e) {
                            done = true;
                            subscriber.onError(e);
                            unsubscribe();
                        }
                        if (shouldEnd) {
                            done = true;
                            subscriber.onCompleted();
                            unsubscribe();
                        }
                    }
                };
            }
        };
    }

    public static<T> Observable<T> rememberLast(final Observable<T> observable) {
        final AtomicReference<T> lastValue = new AtomicReference<>(null);
        return observable.doOnNext(new Action1<T>() {
            @Override
            public void call(final T value) {
                lastValue.set(value);
            }
        }).startWith(Observable.defer(new Func0<Observable<T>>() {
            @Override
            public Observable<T> call() {
                final T last = lastValue.get();
                return last != null ? Observable.just(last) : Observable.<T>empty();
            }
        })).replay(1).refCount();
    }

}
