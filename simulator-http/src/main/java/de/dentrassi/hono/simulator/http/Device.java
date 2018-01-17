package de.dentrassi.hono.simulator.http;

import static de.dentrassi.hono.demo.common.Register.shouldRegister;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.dentrassi.hono.demo.common.Register;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Device {

    private static final Logger logger = LoggerFactory.getLogger(Device.class);

    private static final MediaType JSON = MediaType.parse("application/json");

    private static final String HONO_HTTP_PROTO = System.getenv("HONO_HTTP_PROTO");
    private static final String HONO_HTTP_HOST = System.getenv("HONO_HTTP_HOST");
    private static final String HONO_HTTP_PORT = System.getenv("HONO_HTTP_PORT");
    private static final HttpUrl HONO_HTTP_URL;

    public static final AtomicLong SENT = new AtomicLong();
    public static final AtomicLong SUCCESS = new AtomicLong();
    public static final AtomicLong FAILURE = new AtomicLong();

    private static final boolean ASYNC = Boolean.parseBoolean(System.getenv().getOrDefault("HTTP_ASYNC", "false"));

    private static final boolean AUTO_REGISTER = Boolean
            .parseBoolean(System.getenv().getOrDefault("AUTO_REGISTER", "true"));

    static {
        String url = System.getenv("HONO_HTTP_URL");

        if (url == null && HONO_HTTP_HOST != null && HONO_HTTP_PORT != null) {
            final String proto = HONO_HTTP_PROTO != null ? HONO_HTTP_PROTO : "http";
            url = String.format("%s://%s:%s", proto, HONO_HTTP_HOST, HONO_HTTP_PORT);
        }

        if (url != null) {
            HONO_HTTP_URL = HttpUrl.parse(url).resolve("/telemetry");
        } else {
            HONO_HTTP_URL = null;
        }

        System.out.println("Running Async: " + ASYNC);
    }

    private final OkHttpClient client;

    private final String auth;

    private final RequestBody body;

    private final Request request;

    private final Register register;

    private final String user;

    private final String deviceId;

    private final String password;

    public Device(final String user, final String deviceId, final String tenant, final String password,
            final OkHttpClient client, final Register register) {
        this.client = client;
        this.register = register;
        this.user = user;
        this.deviceId = deviceId;
        this.password = password;
        this.auth = Credentials.basic(user + "@" + tenant, password);
        this.body = RequestBody.create(JSON, "{foo: 42}");
        this.request = new Request.Builder()
                .url(HONO_HTTP_URL)
                .post(this.body)
                .header("Authorization", this.auth)
                .build();
    }

    public void register() throws Exception {
        if (shouldRegister()) {
            this.register.device(this.deviceId, this.user, this.password);
        }
    }

    public void tick() {

        if (HONO_HTTP_URL == null) {
            return;
        }

        final Call call = this.client.newCall(this.request);

        SENT.incrementAndGet();

        try {
            if (ASYNC) {

                call.enqueue(new Callback() {

                    @Override
                    public void onResponse(final Call call, final Response response) throws IOException {
                        if (response.isSuccessful()) {
                            SUCCESS.incrementAndGet();
                        } else {
                            logger.trace("Result code: {}", response.code());
                            FAILURE.incrementAndGet();
                            handleFailure(response.code());
                        }
                        response.close();
                    }

                    @Override
                    public void onFailure(final Call call, final IOException e) {
                        FAILURE.incrementAndGet();
                        logger.debug("Failed to tick", e);
                    }
                });

            } else {

                try (final Response response = call.execute()) {
                    if (response.isSuccessful()) {
                        SUCCESS.incrementAndGet();
                    } else {
                        logger.trace("Result code: {}", response.code());
                        FAILURE.incrementAndGet();
                        handleFailure(response.code());
                    }
                }
            }

        } catch (final IOException e) {
            FAILURE.incrementAndGet();
            logger.debug("Failed to tick", e);
        }

    }

    protected void handleFailure(final int code) {
        try {
            switch (code) {
            case 401:
                if (AUTO_REGISTER) {
                    register();
                }
                break;
            }
        } catch (final Exception e) {
            logger.warn("Failed to handle failure", e);
        }
    }
}
