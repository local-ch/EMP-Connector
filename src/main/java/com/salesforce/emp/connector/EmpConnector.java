/* 
 * Copyright (c) 2016, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license. 
 * For full license text, see LICENSE.TXT file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.emp.connector;

import java.net.ConnectException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.LongPollingTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author hal.hildebrand
 * @since 202
 */
public class EmpConnector {
    private static final String ERROR = "error";
    private static final String FAILURE = "failure";

    private class SubscriptionImpl implements TopicSubscription {
        private final String topic;
        private final Consumer<Map<String, Object>> consumer;

        private SubscriptionImpl(String topic, Consumer<Map<String, Object>> consumer) {
            this.topic = topic;
            this.consumer = consumer;
            subscriptions.add(this);
        }

        /*
         * (non-Javadoc)
         * @see com.salesforce.emp.connector.Subscription#cancel()
         */
        @Override
        public void cancel() {
            replay.remove(topic);
            if (running.get() && client != null) {
                client.getChannel(topic).unsubscribe();
                subscriptions.remove(this);
            }
        }

        /*
         * (non-Javadoc)
         * @see com.salesforce.emp.connector.Subscription#getReplay()
         */
        @Override
        public long getReplayFrom() {
            return replay.getOrDefault(topic, REPLAY_FROM_EARLIEST);
        }

        /*
         * (non-Javadoc)
         * @see com.salesforce.emp.connector.Subscription#getTopic()
         */
        @Override
        public String getTopic() {
            return topic;
        }

        @Override
        public String toString() {
            return String.format("Subscription [%s:%s]", getTopic(), getReplayFrom());
        }

        Future<TopicSubscription> subscribe() {
            Long replayFrom = getReplayFrom();
            ClientSessionChannel channel = client.getChannel(topic);
            CompletableFuture<TopicSubscription> future = new CompletableFuture<>();
            channel.subscribe((c, message) -> consumer.accept(message.getDataAsMap()), (c, message) -> {
                if (message.isSuccessful()) {
                    future.complete(this);
                } else {
                    Object error = message.get(ERROR);
                    if (error == null) {
                        error = message.get(FAILURE);
                    }
                    future.completeExceptionally(
                            new CannotSubscribe(paramsProvider.params().endpoint(), topic, replayFrom, error != null ? error : message));
                }
            });
            return future;
        }
    }

    public static long REPLAY_FROM_EARLIEST = -2L;
    public static long REPLAY_FROM_TIP = -1L;

    private static String AUTHORIZATION = "Authorization";
    private static final Logger log = LoggerFactory.getLogger(EmpConnector.class);

    private volatile BayeuxClient client;
    private final HttpClient httpClient;
    private final BayeuxParametersProvider paramsProvider;
    private final ConcurrentMap<String, Long> replay = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();

    private final Set<SubscriptionImpl> subscriptions = new CopyOnWriteArraySet<>();
    private final Set<MessageListenerInfo> listenerInfos = new CopyOnWriteArraySet<>();

    public EmpConnector(BayeuxParametersProvider paramsProvider) {
        this.paramsProvider = paramsProvider;
        httpClient = new HttpClient(this.paramsProvider.params().sslContextFactory());
        httpClient.getProxyConfiguration().getProxies().addAll(this.paramsProvider.params().proxies());
    }

    /**
     * Start the connector.
     * @return true if connection was established, false otherwise
     */
    public Future<Boolean> start() {
        if (running.compareAndSet(false, true)) {
            return connect();
        }
        CompletableFuture<Boolean> future = new CompletableFuture<Boolean>();
        future.complete(true);
        return future;
    }

    /**
     * Stop the connector
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) { return; }
        if (client != null) {
            client.disconnect();
            client = null;
            subscriptions.clear();
        }
        if (httpClient != null) {
            try {
                httpClient.stop();
            } catch (Exception e) {
                log.error("Unable to stop HTTP transport[{}]", paramsProvider.params().endpoint(), e);
            }
        }
    }

    /**
     * Subscribe to a topic, receiving events after the replayFrom position
     *
     * @param topic
     *            - the topic to subscribe to
     * @param replayFrom
     *            - the replayFrom position in the event stream
     * @param consumer
     *            - the consumer of the events
     * @return a Future returning the Subscription - on completion returns a Subscription or throws a CannotSubscribe
     *         exception
     */
    public Future<TopicSubscription> subscribe(String topic, long replayFrom, Consumer<Map<String, Object>> consumer) {
        if (!running.get()) {
            throw new IllegalStateException(String.format("Connector[%s} has not been started",
                    paramsProvider.params().endpoint()));
        }

        if (replay.putIfAbsent(topic, replayFrom) != null) {
            throw new IllegalStateException(String.format("Already subscribed to %s [%s]",
                    topic, paramsProvider.params().endpoint()));
        }

        SubscriptionImpl subscription = new SubscriptionImpl(topic, consumer);

        return subscription.subscribe();
    }

    /**
     * Subscribe to a topic, receiving events from the earliest event position in the stream
     *
     * @param topic
     *            - the topic to subscribe to
     * @param consumer
     *            - the consumer of the events
     * @return a Future returning the Subscription - on completion returns a Subscription or throws a CannotSubscribe
     *         exception
     */
    public Future<TopicSubscription> subscribeEarliest(String topic, Consumer<Map<String, Object>> consumer) {
        return subscribe(topic, REPLAY_FROM_EARLIEST, consumer);
    }

    /**
     * Subscribe to a topic, receiving events from the latest event position in the stream
     *
     * @param topic
     *            - the topic to subscribe to
     * @param consumer
     *            - the consumer of the events
     * @return a Future returning the Subscription - on completion returns a Subscription or throws a CannotSubscribe
     *         exception
     */
    public Future<TopicSubscription> subscribeTip(String topic, Consumer<Map<String, Object>> consumer) {
        return subscribe(topic, REPLAY_FROM_TIP, consumer);
    }

    public EmpConnector addListener(String channel, ClientSessionChannel.MessageListener messageListener) {
        listenerInfos.add(new MessageListenerInfo(channel, messageListener));
        return this;
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    public boolean isDisconnected() {
        return client != null && client.isDisconnected();
    }

    public boolean isHandshook() {
        return client != null && client.isHandshook();
    }

    public long getLastReplayId(String topic) {
        return replay.get(topic);
    }

    private Future<Boolean> connect() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        replay.clear();
        try {
            httpClient.start();
        } catch (Exception e) {
            log.error("Unable to start HTTP transport[{}]", paramsProvider.params().endpoint(), e);
            running.set(false);
            future.complete(false);
            return future;
        }

        LongPollingTransport httpTransport = new LongPollingTransport(paramsProvider.params().longPollingOptions(), httpClient) {
            @Override
            protected void customize(Request request) {
                request.header(AUTHORIZATION, paramsProvider.params().bearerToken());
            }
        };

        client = new BayeuxClient(paramsProvider.params().endpoint().toExternalForm(), httpTransport);

        client.addExtension(new ReplayExtension(replay));

        addListeners(client);

        client.handshake((c, m) -> {
            if (!m.isSuccessful()) {
                Object error = m.get(ERROR);
                if (error == null) {
                    error = m.get(FAILURE);
                }
                future.completeExceptionally(new ConnectException(
                        String.format("Cannot connect [%s] : %s", paramsProvider.params().endpoint(), error)));
                running.set(false);
            } else {
                subscriptions.forEach(SubscriptionImpl::subscribe);
                future.complete(true);
            }
        });

        return future;
    }

    private void addListeners(BayeuxClient client) {
        for (MessageListenerInfo info : listenerInfos) {
            client.getChannel(info.getChannelName()).addListener(info.getMessageListener());
        }
    }

    private static class MessageListenerInfo {
        private String channelName;
        private ClientSessionChannel.MessageListener messageListener;

        MessageListenerInfo(String channelName, ClientSessionChannel.MessageListener messageListener) {
            this.channelName = channelName;
            this.messageListener = messageListener;
        }

        String getChannelName() {
            return channelName;
        }

        ClientSessionChannel.MessageListener getMessageListener() {
            return messageListener;
        }
    }
}
