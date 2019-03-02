/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dromara.myth.core.disruptor.publisher;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.IgnoreExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import org.dromara.myth.common.bean.entity.MythTransaction;
import org.dromara.myth.common.config.MythConfig;
import org.dromara.myth.common.enums.EventTypeEnum;
import org.dromara.myth.core.concurrent.threadpool.MythTransactionThreadFactory;
import org.dromara.myth.core.coordinator.MythCoordinatorService;
import org.dromara.myth.core.disruptor.event.MythTransactionEvent;
import org.dromara.myth.core.disruptor.factory.MythTransactionEventFactory;
import org.dromara.myth.core.disruptor.handler.MythTransactionEventHandler;
import org.dromara.myth.core.disruptor.translator.MythTransactionEventTranslator;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MythTransactionEventPublisher.
 *
 * @author xiaoyu(Myth)
 */
@Component
public class MythTransactionEventPublisher implements DisposableBean, ApplicationListener<ContextRefreshedEvent> {

    private static final int MAX_THREAD = Runtime.getRuntime().availableProcessors() << 1;

    private static final AtomicLong INDEX = new AtomicLong(1);

    private Disruptor<MythTransactionEvent> disruptor;

    private final MythCoordinatorService coordinatorService;

    private final MythConfig mythConfig;

    @Autowired
    public MythTransactionEventPublisher(MythCoordinatorService coordinatorService, MythConfig mythConfig) {
        this.coordinatorService = coordinatorService;
        this.mythConfig = mythConfig;
    }

    /**
     * start disruptor.
     *
     * @param bufferSize bufferSize
     */
    private void start(final int bufferSize, final int threadSize) {
        disruptor = new Disruptor<>(new MythTransactionEventFactory(), bufferSize, runnable -> {
            return new Thread(new ThreadGroup("hmily-disruptor"), runnable,
                    "disruptor-thread-" + INDEX.getAndIncrement());
        }, ProducerType.MULTI, new BlockingWaitStrategy());
        final Executor executor = new ThreadPoolExecutor(MAX_THREAD, MAX_THREAD, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                MythTransactionThreadFactory.create("myth-log-disruptor", false),
                new ThreadPoolExecutor.AbortPolicy());

        MythTransactionEventHandler[] consumers = new MythTransactionEventHandler[MAX_THREAD];
        for (int i = 0; i < threadSize; i++) {
            consumers[i] = new MythTransactionEventHandler(coordinatorService, executor);
        }
        disruptor.handleEventsWithWorkerPool(consumers);
        disruptor.setDefaultExceptionHandler(new IgnoreExceptionHandler());
        disruptor.start();
    }


    /**
     * publish disruptor event.
     *
     * @param mythTransaction {@linkplain MythTransaction }
     * @param type            {@linkplain EventTypeEnum}
     */
    public void publishEvent(final MythTransaction mythTransaction, final int type) {
        final RingBuffer<MythTransactionEvent> ringBuffer = disruptor.getRingBuffer();
        ringBuffer.publishEvent(new MythTransactionEventTranslator(type), mythTransaction);
    }

    @Override
    public void destroy() {
        disruptor.shutdown();
    }

    @Override
    public void onApplicationEvent(final ContextRefreshedEvent contextRefreshedEvent) {
        start(mythConfig.getBufferSize(), mythConfig.getConsumerThreads());
    }
}
