/*
 * Copyright (C) 2016 Airbnb, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.airbnb.rxgroups;

import org.junit.Test;
import org.reactivestreams.Subscription;

import java.util.concurrent.TimeUnit;


import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.Observer;
import io.reactivex.functions.Action;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.ReplaySubject;

import static org.assertj.core.api.Assertions.assertThat;

public class SubscriptionProxyTest {
    @Test
    public void testInitialState() {
        Observable<Integer> observable = Observable.create(new ObservableOnSubscribe<Integer>() {
            @Override
            public void subscribe(ObservableEmitter<Integer> emitter) throws Exception {
                emitter.onNext(1234);
                emitter.onComplete();
            }
        });

        SubscriptionProxy<Integer> proxy = SubscriptionProxy.create(observable);
        assertThat(proxy.isUnsubscribed()).isEqualTo(false);
        assertThat(proxy.isCancelled()).isEqualTo(false);
    }

    @Test
    public void testSubscriptionState() {
        TestObserver<String> observer = new TestObserver<>();
        PublishSubject<String> subject = PublishSubject.create();
        SubscriptionProxy<String> proxy = SubscriptionProxy.create(subject);

        proxy.subscribe(observer);
        assertThat(proxy.isUnsubscribed()).isEqualTo(false);
        assertThat(proxy.isCancelled()).isEqualTo(false);

        proxy.unsubscribe();

        assertThat(proxy.isUnsubscribed()).isEqualTo(true);
        assertThat(proxy.isCancelled()).isEqualTo(false);

        proxy.cancel();

        assertThat(proxy.isUnsubscribed()).isEqualTo(true);
        assertThat(proxy.isCancelled()).isEqualTo(true);
    }

    @Test
    public void testSubscribe() {
        TestObserver<Integer> observer = new TestObserver<>();
        Observable<Integer> observable = Observable.create(new ObservableOnSubscribe<Integer>() {
            @Override
            public void subscribe(ObservableEmitter<Integer> observer) {
                observer.onNext(1234);
                observer.onComplete();
            }
        });
        SubscriptionProxy<Integer> proxy = SubscriptionProxy.create(observable);

        proxy.subscribe(observer);

        observer.assertCompleted();
        observer.assertValue(1234);
    }

    @Test
    public void testUnsubscribe() {
        TestObserver<String> observer = new TestObserver<>();
        PublishSubject<String> subject = PublishSubject.create();
        SubscriptionProxy<String> proxy = SubscriptionProxy.create(subject);

        proxy.subscribe(observer);
        proxy.unsubscribe();

        subject.onNext("Avanti!");
        subject.onComplete();

        assertThat(proxy.isUnsubscribed()).isEqualTo(true);
        observer.awaitTerminalEvent(10, TimeUnit.MILLISECONDS);
        observer.assertNotCompleted();
        observer.assertNoValues();
    }

    private static class TestOnUnsubscribe implements Action {
        boolean called = false;

        @Override
        public void run() throws Exception {
            called = true;
        }
    }

    @Test
    public void testCancelShouldUnsubscribeFromSourceObservable() {
        TestObserver<String> observer = new TestObserver<>();
        final TestOnUnsubscribe onUnsubscribe = new TestOnUnsubscribe();
        Observable<String> observable = Observable.create(new ObservableOnSubscribe<String>() {
            @Override
            public void subscribe(final ObservableEmitter<String> emitter) {
                //emitter.add(Subscriptions.create(onUnsubscribe));
            }
        });
        SubscriptionProxy<String> proxy = SubscriptionProxy.create(observable);

        proxy.subscribe(observer);
        proxy.cancel();

        assertThat(proxy.isUnsubscribed()).isEqualTo(true);
        assertThat(proxy.isCancelled()).isEqualTo(true);
        //assertThat(onUnsubscribe.called).isEqualTo(true);
    }

    @Test
    public void testUnsubscribeShouldNotUnsubscribeFromSourceObservable() {
        TestObserver<String> observer = new TestObserver<>();
        final TestOnUnsubscribe onUnsubscribe = new TestOnUnsubscribe();
        Observable<String> observable = Observable.create(new ObservableOnSubscribe<String>() {
            @Override
            public void subscribe(final ObservableEmitter<String> observer) {
                //observer.add(Subscriptions.create(onUnsubscribe));
            }
        }).share();
        SubscriptionProxy<String> proxy = SubscriptionProxy.create(observable);

        proxy.subscribe(observer);
        proxy.unsubscribe();

        assertThat(proxy.isUnsubscribed()).isEqualTo(true);
        assertThat(proxy.isCancelled()).isEqualTo(false);
        //assertThat(onUnsubscribe.called).isEqualTo(false);
    }

    @Test
    public void testUnsubscribeBeforeEmit() {
        TestObserver<String> observer = new TestObserver<>();
        ReplaySubject<String> subject = ReplaySubject.create();
        SubscriptionProxy<String> proxy = SubscriptionProxy.create(subject);

        proxy.subscribe(observer);
        proxy.unsubscribe();

        observer.assertNotCompleted();
        observer.assertNoValues();

        subject.onNext("Avanti!");
        subject.onComplete();

        proxy.subscribe(observer);
        observer.assertCompleted();
        observer.assertValue("Avanti!");
    }

    @Test
    public void shouldCacheResultsWhileUnsubscribedAndDeliverAfterResubscription() {
        TestObserver<String> observer = new TestObserver<>();
        ReplaySubject<String> subject = ReplaySubject.create();
        SubscriptionProxy<String> proxy = SubscriptionProxy.create(subject);

        proxy.subscribe(observer);
        proxy.unsubscribe();

        observer.assertNoValues();

        subject.onNext("Avanti!");
        subject.onComplete();

        proxy.subscribe(observer);

        observer.awaitTerminalEvent(3, TimeUnit.SECONDS);
        observer.assertValue("Avanti!");
    }

    @Test
    public void shouldRedeliverSameResultsToDifferentSubscriber() {
        // Use case: When rotating an activity, ObservableManager will re-subscribe original request's
        // Observable to a new Observer, which is a member of the new activity instance. In this
        // case, we may want to redeliver any previous results (if the request is still being
        // managed by ObservableManager).
        TestObserver<String> observer = new TestObserver<>();
        ReplaySubject<String> subject = ReplaySubject.create();
        SubscriptionProxy<String> proxy = SubscriptionProxy.create(subject);

        proxy.subscribe(observer);

        subject.onNext("Avanti!");
        subject.onComplete();

        proxy.unsubscribe();

        TestObserver<String> newSubscriber = new TestObserver<>();
        proxy.subscribe(newSubscriber);

        newSubscriber.awaitTerminalEvent(3, TimeUnit.SECONDS);
        newSubscriber.assertCompleted();
        newSubscriber.assertValue("Avanti!");

        observer.assertCompleted();
        observer.assertValue("Avanti!");
    }

    @Test
    public void multipleSubscribesForSameObserverShouldBeIgnored() {
        TestObserver<String> observer = new TestObserver<>();
        PublishSubject<String> subject = PublishSubject.create();
        SubscriptionProxy<String> proxy = SubscriptionProxy.create(subject);

        proxy.subscribe(observer);
        proxy.subscribe(observer);
        proxy.unsubscribe();

        subject.onNext("Avanti!");
        subject.onComplete();

        assertThat(proxy.isUnsubscribed()).isEqualTo(true);
        observer.awaitTerminalEvent(10, TimeUnit.MILLISECONDS);
        observer.assertNotCompleted();
        observer.assertNoValues();
    }

    @Test
    public void shouldKeepDeliveringEventsAfterResubscribed() {
        TestObserver<String> observer = new TestObserver<>();
        ReplaySubject<String> subject = ReplaySubject.create();
        SubscriptionProxy<String> proxy = SubscriptionProxy.create(subject);

        proxy.subscribe(observer);
        subject.onNext("Avanti 1");
        proxy.unsubscribe();
        observer = new TestObserver<>();
        proxy.subscribe(observer);

        subject.onNext("Avanti!");

        observer.assertValues("Avanti 1", "Avanti!");
    }
}
