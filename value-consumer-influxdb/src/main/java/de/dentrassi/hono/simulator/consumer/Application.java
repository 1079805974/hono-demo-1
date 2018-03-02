/*******************************************************************************
 * Copyright (c) 2017 Red Hat Inc and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Jens Reimann - initial API and implementation
 *******************************************************************************/
package de.dentrassi.hono.simulator.consumer;

import static java.lang.Integer.parseInt;
import static java.lang.System.getenv;
import static java.util.Optional.ofNullable;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.qpid.proton.amqp.messaging.AmqpValue;
import org.apache.qpid.proton.amqp.messaging.Data;
import org.apache.qpid.proton.amqp.messaging.Section;
import org.apache.qpid.proton.message.Message;
import org.eclipse.hono.client.HonoClient;
import org.eclipse.hono.client.MessageConsumer;
import org.eclipse.hono.client.impl.HonoClientImpl;
import org.eclipse.hono.config.ClientConfigProperties;
import org.eclipse.hono.connection.ConnectionFactoryImpl;
import org.eclipse.hono.connection.ConnectionFactoryImpl.ConnectionFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.dentrassi.hono.demo.common.InfluxDbMetrics;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.proton.ProtonClientOptions;
import io.vertx.proton.ProtonConnection;

public class Application {

    private static final Logger logger = LoggerFactory.getLogger(Application.class);

    private final Vertx vertx;
    private final HonoClientImpl honoClient;
    private final CountDownLatch latch;
    private final String tenant;

    private final InfluxDbConsumer consumer;
    private final InfluxDbMetrics metrics;

    private long last;
    private final AtomicLong counter = new AtomicLong();

    private final ScheduledExecutorService stats;

    private static final boolean PERSISTENCE_ENABLED = Optional
            .ofNullable(System.getenv("ENABLE_PERSISTENCE"))
            .map(Boolean::parseBoolean)
            .orElse(true);

    private static final boolean METRICS_ENABLED = Optional
            .ofNullable(System.getenv("ENABLE_METRICS"))
            .map(Boolean::parseBoolean)
            .orElse(true);

    private static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = 5_000;

    public static void main(final String[] args) throws Exception {

        final Application app = new Application(
                getenv("HONO_TENANT"),
                getenv("MESSAGING_SERVICE_HOST"), // HONO_DISPATCH_ROUTER_EXT_SERVICE_HOST 
                Integer.parseInt(getenv("MESSAGING_SERVICE_PORT_AMQP")), // HONO_DISPATCH_ROUTER_EXT_SERVICE_PORT
                getenv("HONO_USER"),
                getenv("HONO_PASSWORD"),
                ofNullable(getenv("HONO_TRUSTED_CERTS")));

        try {
            app.consumeTelemetryData();
            System.out.println("Exiting application ...");
        } finally {
            app.close();
        }
        System.out.println("Bye, bye!");

        Thread.sleep(1_000);

        for (final Thread t : Thread.getAllStackTraces().keySet()) {
            System.out.println(t.getName());
        }

        System.exit(-1);
    }

    public Application(final String tenant, final String host, final int port, final String user, final String password,
            final Optional<String> trustedCerts) {

        System.out.format("Hono Consumer - Server: %s:%s%n", host, port);

        if (PERSISTENCE_ENABLED) {
            logger.info("Recording payload");
            this.consumer = new InfluxDbConsumer(makeInfluxDbUrl(),
                    getenv("INFLUXDB_USER"),
                    getenv("INFLUXDB_PASSWORD"),
                    getenv("INFLUXDB_NAME"));
        } else {
            this.consumer = null;
        }

        if (METRICS_ENABLED) {
            logger.info("Recording metrics");
            this.metrics = new InfluxDbMetrics(makeInfluxDbUrl(),
                    getenv("INFLUXDB_USER"),
                    getenv("INFLUXDB_PASSWORD"),
                    getenv("INFLUXDB_NAME"));
        } else {
            this.metrics = null;
        }

        this.stats = Executors.newSingleThreadScheduledExecutor();
        this.stats.scheduleAtFixedRate(this::updateStats, 1, 1, TimeUnit.SECONDS);

        this.tenant = tenant;

        this.vertx = Vertx.vertx();

        final ConnectionFactoryBuilder builder = ConnectionFactoryImpl.ConnectionFactoryBuilder.newBuilder()
                .vertx(this.vertx)
                .host(host).port(port)
                .user(user).password(password);

        if (System.getenv("DISABLE_TLS") == null) {
            builder.enableTls()
                    .disableHostnameVerification();
        }

        trustedCerts.ifPresent(builder::trustStorePath);

        final ClientConfigProperties config = new ClientConfigProperties();

        if (System.getenv("HONO_INITIAL_CREDITS") != null) {
            config.setInitialCredits(parseInt(System.getenv("HONO_INITIAL_CREDITS")));
        }

        this.honoClient = new HonoClientImpl(this.vertx, builder.build(), config);

        this.latch = new CountDownLatch(1);
    }

    private void close() {
        this.stats.shutdown();
        this.honoClient.shutdown(done -> {
        });
        this.vertx.close();
    }

    public void updateStats() {
        final long c = this.counter.get();

        final long diff = c - this.last;
        this.last = c;

        final Instant now = Instant.now();

        System.out.format("%s: Processed %s messages%n", now, diff);

        try {
            if (this.metrics != null) {
                this.metrics.updateStats(now, "consumer", "messageCount", diff);
            }
        } catch (final Exception e) {
            e.printStackTrace();
        }
    }

    private static String makeInfluxDbUrl() {
        final String url = getenv("INFLUXDB_URL");
        if (url != null && !url.isEmpty()) {
            return url;
        }

        return String.format("http://%s:%s", getenv("INFLUXDB_SERVICE_HOST"), getenv("INFLUXDB_SERVICE_PORT_API"));
    }

    private ProtonClientOptions getOptions() {
        return new ProtonClientOptions();
    }

    private void consumeTelemetryData() throws Exception {

        final Future<MessageConsumer> startupTracker = Future.future();
        startupTracker.setHandler(startup -> {
            if (startup.failed()) {
                logger.error("Error occurred during initialization of receiver", startup.cause());
                this.latch.countDown();
            }
        });

        this.honoClient
                .connect(getOptions(), this::onDisconnect)
                .compose(connectedClient -> createConsumer(connectedClient))
                .setHandler(startupTracker);

        // if everything went according to plan, the next step will block forever

        this.latch.await();
    }

    private Future<MessageConsumer> createConsumer(final HonoClient connectedClient) {

        // default is telemetry consumer
        return connectedClient.createTelemetryConsumer(this.tenant,
                this::handleTelemetryMessage, closeHandler -> {
                    logger.info("close handler of event consumer is called");
                    this.vertx.setTimer(DEFAULT_CONNECT_TIMEOUT_MILLIS, reconnect -> {
                        logger.info("attempting to re-open the EventConsumer link ...");
                        createConsumer(connectedClient);
                    });
                });
    }

    private void onDisconnect(final ProtonConnection con) {
        this.vertx.setTimer(DEFAULT_CONNECT_TIMEOUT_MILLIS, reconnect -> {
            logger.info("attempting to re-connect to Hono ...");
            this.honoClient.connect(getOptions(), this::onDisconnect)
                    .compose(connectedClient -> createConsumer(connectedClient));
        });
    }

    private void handleTelemetryMessage(final Message msg) {
        this.counter.incrementAndGet();

        if (this.consumer != null) {
            final String body = bodyAsString(msg);
            if (body != null) {
                this.consumer.consume(msg, body);
            }
        }
    }

    private String bodyAsString(final Message msg) {

        final Section body = msg.getBody();

        if (body instanceof AmqpValue) {

            final Object value = ((AmqpValue) body).getValue();

            if (value == null) {
                logger.info("Missing body value");
                return null;
            }

            if (value instanceof String) {
                return (String) value;
            } else if (value instanceof byte[]) {
                return new String((byte[]) value, StandardCharsets.UTF_8);
            } else {
                logger.info("Unsupported body type: {}", value.getClass());
                return null;
            }
        } else if (body instanceof Data) {
            return StandardCharsets.UTF_8.decode(((Data) body).getValue().asByteBuffer()).toString();
        } else {
            logger.info("Unsupported body type: {}", body.getClass());
            return null;
        }
    }

}
