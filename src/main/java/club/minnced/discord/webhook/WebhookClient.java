/*
 * Copyright 2018-2020 Florian Spieß
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package club.minnced.discord.webhook;

import club.minnced.discord.webhook.exception.HttpException;
import club.minnced.discord.webhook.receive.EntityFactory;
import club.minnced.discord.webhook.receive.ReadonlyMessage;
import club.minnced.discord.webhook.send.AllowedMentions;
import club.minnced.discord.webhook.send.WebhookEmbed;
import club.minnced.discord.webhook.send.WebhookMessage;
import club.minnced.discord.webhook.send.WebhookMessageBuilder;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jetbrains.annotations.Async;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnegative;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.regex.Matcher;

/**
 * Client used to execute webhooks. All send methods are async and return a {@link java.util.concurrent.CompletableFuture}
 * representing the execution. If provided with {@code null} an {@link java.lang.NullPointerException} is thrown instead.
 */
public class WebhookClient implements AutoCloseable {
    /**
     * Format for webhook execution endpoint
     */
    public static final String WEBHOOK_URL = "https://discord.com/api/v" + LibraryInfo.DISCORD_API_VERSION + "/webhooks/%s/%s";
    /** User-Agent used for REST requests */
    public static final String USER_AGENT = "Webhook(https://github.com/MinnDevelopment/discord-webhooks, " + LibraryInfo.VERSION + ")";
    private static final Logger LOG = LoggerFactory.getLogger(WebhookClient.class);

    protected final String url;
    protected final long id;
    protected final OkHttpClient client;
    protected final ScheduledExecutorService pool;
    protected final Bucket bucket;
    protected final BlockingQueue<Request> queue;
    protected final boolean parseMessage;
    protected final AllowedMentions allowedMentions;
    protected long defaultTimeout;
    protected volatile boolean isQueued;
    protected boolean isShutdown;

    protected WebhookClient(
            final long id, final String token, final boolean parseMessage,
            final OkHttpClient client, final ScheduledExecutorService pool, AllowedMentions mentions) {
        this.client = client;
        this.id = id;
        this.parseMessage = parseMessage;
        this.url = String.format(WEBHOOK_URL, Long.toUnsignedString(id), token);
        this.pool = pool;
        this.bucket = new Bucket();
        this.queue = new LinkedBlockingQueue<>();
        this.allowedMentions = mentions;
        this.isQueued = false;
    }

    /**
     * Factory method to create a basic WebhookClient with the provided id and token.
     *
     * @param  id
     *         The webhook id
     * @param  token
     *         The webhook token
     *
     * @throws java.lang.NullPointerException
     *         If provided with null
     *
     * @return The WebhookClient for the provided id and token
     */
    public static WebhookClient withId(long id, @NotNull String token) {
        Objects.requireNonNull(token, "Token");
        ScheduledExecutorService pool = WebhookClientBuilder.getDefaultPool(id, null, false);
        return new WebhookClient(id, token, true, new OkHttpClient(), pool, AllowedMentions.all());
    }

    /**
     * Factory method to create a basic WebhookClient with the provided id and token.
     *
     * @param  url
     *         The url for the webhook
     *
     * @throws java.lang.NullPointerException
     *         If provided with null
     * @throws java.lang.NumberFormatException
     *         If no valid id is part o the url
     *
     * @return The WebhookClient for the provided url
     */
    public static WebhookClient withUrl(@NotNull String url) {
        Objects.requireNonNull(url, "URL");
        Matcher matcher = WebhookClientBuilder.WEBHOOK_PATTERN.matcher(url);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Failed to parse webhook URL");
        }
        return withId(Long.parseUnsignedLong(matcher.group(1)), matcher.group(2));
    }

    /**
     * The id for this webhook
     *
     * @return The id
     */
    public long getId() {
        return id;
    }

    /**
     * The URL for this webhook formatted using {@link #WEBHOOK_URL} unless
     * specified by {@link club.minnced.discord.webhook.WebhookClientBuilder#WebhookClientBuilder(String)} explicitly
     *
     * @return The URL for this webhook
     */
    @NotNull
    public String getUrl() {
        return url;
    }

    /**
     * Whether futures will receive {@link club.minnced.discord.webhook.receive.ReadonlyMessage} instances
     * or {@code null}.
     *
     * @return True, if messages will be received - false otherwise
     */
    public boolean isWait() {
        return parseMessage;
    }

    /**
     * Whether this client has been shutdown.
     *
     * @return True, if client is closed
     */
    public boolean isShutdown() {
        return isShutdown;
    }

    /**
     * Configure a default timeout to use for requests.
     * <br>The {@link CompletableFuture} returned by the various send methods will be completed exceptionally with a {@link TimeoutException} when the timeout expires.
     * By default, no timeout is used.
     *
     * <p>Note that this timeout is independent from the timeouts configured in {@link OkHttpClient} and will only prevent queued requests from being executed.
     *
     * @param  millis
     *         The timeout in milliseconds, or 0 for no timeout
     *
     * @throws IllegalArgumentException
     *         If the provided timeout is negative
     *
     * @return The current WebhookClient instance
     */
    @NotNull
    @SuppressWarnings("ConstantConditions") // we still need a runtime check for negative numbers
    public WebhookClient setTimeout(@Nonnegative long millis) {
        if (millis < 0)
            throw new IllegalArgumentException("Cannot set a negative timeout");
        this.defaultTimeout = millis;
        return this;
    }

    /**
     * The current timeout configured by {@link #setTimeout(long)}.
     * <br>If no timeout was configured, this returns 0.
     *
     * @return The timeout in milliseconds or 0 for no timeout
     */
    public long getTimeout() {
        return defaultTimeout;
    }

    /**
     * Sends the provided {@link club.minnced.discord.webhook.send.WebhookMessage}
     * to the webhook.
     * <br>The returned future receives {@code null} if {@link club.minnced.discord.webhook.WebhookClientBuilder#setWait(boolean)}
     * was set to false.
     *
     * <p><b>This will override the default {@link AllowedMentions} of this client!</b>
     *
     * @param  message
     *         The message to send
     *
     * @return {@link java.util.concurrent.CompletableFuture}
     *
     * @see    #isWait()
     */
    @NotNull
    public CompletableFuture<ReadonlyMessage> send(@NotNull WebhookMessage message) {
        Objects.requireNonNull(message, "WebhookMessage");
        return execute(message.getBody());
    }

    /**
     * Sends the provided {@link java.io.File} to the webhook.
     * <br>The returned future receives {@code null} if {@link club.minnced.discord.webhook.WebhookClientBuilder#setWait(boolean)}
     * was set to false.
     *
     * @param  file
     *         The file to send
     *
     * @return {@link java.util.concurrent.CompletableFuture}
     *
     * @see    #isWait()
     * @see    #send(club.minnced.discord.webhook.send.WebhookMessage)
     */
    @NotNull
    public CompletableFuture<ReadonlyMessage> send(@NotNull File file) {
        Objects.requireNonNull(file, "File");
        return send(file, file.getName());
    }

    /**
     * Sends the provided {@link java.io.File} to the webhook.
     * <br>The returned future receives {@code null} if {@link club.minnced.discord.webhook.WebhookClientBuilder#setWait(boolean)}
     * was set to false.
     *
     * @param  file
     *         The file to send
     * @param  fileName
     *         The alternative name to use for this file
     *
     * @return {@link java.util.concurrent.CompletableFuture}
     *
     * @see    #isWait()
     * @see    #send(club.minnced.discord.webhook.send.WebhookMessage)
     */
    @NotNull
    public CompletableFuture<ReadonlyMessage> send(@NotNull File file, @NotNull String fileName) {
        return send(new WebhookMessageBuilder()
                .setAllowedMentions(allowedMentions)
                .addFile(fileName, file)
                .build());
    }

    /**
     * Sends the provided {@code byte[]} to the webhook.
     * <br>The returned future receives {@code null} if {@link club.minnced.discord.webhook.WebhookClientBuilder#setWait(boolean)}
     * was set to false.
     *
     * @param  data
     *         The data to send as a file
     * @param  fileName
     *         The file name to use
     *
     * @return {@link java.util.concurrent.CompletableFuture}
     *
     * @see    #isWait()
     * @see    #send(club.minnced.discord.webhook.send.WebhookMessage)
     */
    @NotNull
    public CompletableFuture<ReadonlyMessage> send(@NotNull byte[] data, @NotNull String fileName) {
        return send(new WebhookMessageBuilder()
                .setAllowedMentions(allowedMentions)
                .addFile(fileName, data)
                .build());
    }

    /**
     * Sends the provided {@link java.io.InputStream} to the webhook.
     * <br>The returned future receives {@code null} if {@link club.minnced.discord.webhook.WebhookClientBuilder#setWait(boolean)}
     * was set to false.
     *
     * @param  data
     *         The data to send as a file
     * @param  fileName
     *         The file name to use
     *
     * @return {@link java.util.concurrent.CompletableFuture}
     *
     * @see    #isWait()
     * @see    #send(club.minnced.discord.webhook.send.WebhookMessage)
     */
    @NotNull
    public CompletableFuture<ReadonlyMessage> send(@NotNull InputStream data, @NotNull String fileName) {
        return send(new WebhookMessageBuilder()
                .setAllowedMentions(allowedMentions)
                .addFile(fileName, data)
                .build());
    }

    /**
     * Sends the provided {@link club.minnced.discord.webhook.send.WebhookEmbed} to the webhook.
     * <br>The returned future receives {@code null} if {@link club.minnced.discord.webhook.WebhookClientBuilder#setWait(boolean)}
     * was set to false.
     *
     * @param  first
     *         The first embed to send
     * @param  embeds
     *         Optional additional embeds to send, up to 10
     *
     * @return {@link java.util.concurrent.CompletableFuture}
     *
     * @see    #isWait()
     * @see    #send(club.minnced.discord.webhook.send.WebhookMessage)
     */
    @NotNull
    public CompletableFuture<ReadonlyMessage> send(@NotNull WebhookEmbed first, @NotNull WebhookEmbed... embeds) {
        return send(WebhookMessage.embeds(first, embeds));
    }

    /**
     * Sends the provided {@link club.minnced.discord.webhook.send.WebhookEmbed} to the webhook.
     * <br>The returned future receives {@code null} if {@link club.minnced.discord.webhook.WebhookClientBuilder#setWait(boolean)}
     * was set to false.
     *
     * @param  embeds
     *         The embeds to send
     *
     * @return {@link java.util.concurrent.CompletableFuture}
     *
     * @see    #isWait()
     * @see    #send(club.minnced.discord.webhook.send.WebhookMessage)
     */
    @NotNull
    public CompletableFuture<ReadonlyMessage> send(@NotNull Collection<WebhookEmbed> embeds) {
        return send(WebhookMessage.embeds(embeds));
    }

    /**
     * Sends the provided content as normal message to the webhook.
     * <br>The returned future receives {@code null} if {@link club.minnced.discord.webhook.WebhookClientBuilder#setWait(boolean)}
     * was set to false.
     *
     * @param  content
     *         The content to send
     *
     * @return {@link java.util.concurrent.CompletableFuture}
     *
     * @see    #isWait()
     * @see    #send(club.minnced.discord.webhook.send.WebhookMessage)
     */
    @NotNull
    public CompletableFuture<ReadonlyMessage> send(@NotNull String content) {
        Objects.requireNonNull(content, "Content");
        content = content.trim();
        if (content.isEmpty())
            throw new IllegalArgumentException("Cannot send an empty message");
        if (content.length() > 2000)
            throw new IllegalArgumentException("Content may not exceed 2000 characters");
        return execute(newBody(newJson().put("content", content).toString()));
    }

    /**
     * Edits the target message and updates it with the provided {@link club.minnced.discord.webhook.send.WebhookMessage}
     * to the webhook.
     * <br>The returned future receives {@code null} if {@link club.minnced.discord.webhook.WebhookClientBuilder#setWait(boolean)}
     * was set to false.
     *
     * <p><b>This will override the default {@link AllowedMentions} of this client!</b>
     *
     * @param  messageId
     *         The target message id
     * @param  message
     *         The message to send
     *
     * @return {@link java.util.concurrent.CompletableFuture}
     *
     * @see    #isWait()
     */
    @NotNull
    public CompletableFuture<ReadonlyMessage> edit(long messageId, @NotNull WebhookMessage message) {
        Objects.requireNonNull(message, "WebhookMessage");
        return execute(message.getBody(), Long.toUnsignedString(messageId), RequestType.EDIT);
    }

    /**
     * Edits the target message and updates it with the provided {@link club.minnced.discord.webhook.send.WebhookEmbed} to the webhook.
     * <br>The returned future receives {@code null} if {@link club.minnced.discord.webhook.WebhookClientBuilder#setWait(boolean)}
     * was set to false.
     *
     * @param  messageId
     *         The target message id
     * @param  first
     *         The first embed to send
     * @param  embeds
     *         Optional additional embeds to send, up to 10
     *
     * @return {@link java.util.concurrent.CompletableFuture}
     *
     * @see    #isWait()
     * @see    #edit(long, club.minnced.discord.webhook.send.WebhookMessage)
     */
    @NotNull
    public CompletableFuture<ReadonlyMessage> edit(long messageId, @NotNull WebhookEmbed first, @NotNull WebhookEmbed... embeds) {
        return edit(messageId, WebhookMessage.embeds(first, embeds));
    }

    /**
     * Edits the target message and updates it with the provided {@link club.minnced.discord.webhook.send.WebhookEmbed} to the webhook.
     * <br>The returned future receives {@code null} if {@link club.minnced.discord.webhook.WebhookClientBuilder#setWait(boolean)}
     * was set to false.
     *
     * @param  messageId
     *         The target message id
     * @param  embeds
     *         The embeds to send
     *
     * @return {@link java.util.concurrent.CompletableFuture}
     *
     * @see    #isWait()
     * @see    #edit(long, club.minnced.discord.webhook.send.WebhookMessage)
     */
    @NotNull
    public CompletableFuture<ReadonlyMessage> edit(long messageId, @NotNull Collection<WebhookEmbed> embeds) {
        return edit(messageId, WebhookMessage.embeds(embeds));
    }

    /**
     * Edits the target message and updates it with the provided content as normal message to the webhook.
     * <br>The returned future receives {@code null} if {@link club.minnced.discord.webhook.WebhookClientBuilder#setWait(boolean)}
     * was set to false.
     *
     * @param  messageId
     *         The target message id
     * @param  content
     *         The content to send
     *
     * @return {@link java.util.concurrent.CompletableFuture}
     *
     * @see    #isWait()
     * @see    #edit(long, club.minnced.discord.webhook.send.WebhookMessage)
     */
    @NotNull
    public CompletableFuture<ReadonlyMessage> edit(long messageId, @NotNull String content) {
        Objects.requireNonNull(content, "Content");
        content = content.trim();
        if (content.isEmpty())
            throw new IllegalArgumentException("Cannot send an empty message");
        if (content.length() > 2000)
            throw new IllegalArgumentException("Content may not exceed 2000 characters");
        return edit(messageId, new WebhookMessageBuilder().setContent(content).build());
    }

    /**
     * Deletes the message with the provided ID.
     *
     * @param  messageId
     *         The target message id
     *
     * @return {@link java.util.concurrent.CompletableFuture}
     */
    @NotNull
    public CompletableFuture<Void> delete(long messageId) {
        return execute(null, Long.toUnsignedString(messageId), RequestType.DELETE).thenApply(v -> null);
    }


    private JSONObject newJson()
    {
        JSONObject json = new JSONObject();
        json.put("allowed_mentions", allowedMentions);
        return json;
    }

    /**
     * Stops the executor used by this pool
     */
    @Override
    public void close() {
        isShutdown = true;
        if (queue.isEmpty())
            pool.shutdown();
    }

    protected void checkShutdown() {
        if (isShutdown)
            throw new RejectedExecutionException("Cannot send to closed client!");
    }

    @NotNull
    protected static RequestBody newBody(String object) {
        return RequestBody.create(IOUtil.JSON, object);
    }

    @NotNull
    protected CompletableFuture<ReadonlyMessage> execute(RequestBody body, @Nullable String messageId, @NotNull RequestType type) {
        checkShutdown();
        String endpoint = url;
        if (type != RequestType.SEND)
            endpoint += "/messages/" + messageId;
        if (parseMessage)
            endpoint += "?wait=true";
        return queueRequest(endpoint, type.method, body);
    }

    @NotNull
    protected CompletableFuture<ReadonlyMessage> execute(RequestBody body) {
        return execute(body, null, RequestType.SEND);
    }

    @NotNull
    protected static HttpException failure(Response response) throws IOException {
        final InputStream stream = IOUtil.getBody(response);
        final String responseBody = stream == null ? "" : new String(IOUtil.readAllBytes(stream));

        return new HttpException(response.code(), responseBody, response.headers());
    }

    @NotNull
    protected CompletableFuture<ReadonlyMessage> queueRequest(String url, String method, RequestBody body) {
        final boolean wasQueued = isQueued;
        isQueued = true;
        CompletableFuture<ReadonlyMessage> callback = new CompletableFuture<>();
        Request req = new Request(callback, body, method, url);
        if (defaultTimeout > 0)
            req.deadline = System.currentTimeMillis() + defaultTimeout;
        enqueuePair(req);
        if (!wasQueued)
            backoffQueue();
        return callback;
    }

    @NotNull
    protected okhttp3.Request newRequest(Request request) {
        return new okhttp3.Request.Builder()
                .url(request.url)
                .method(request.method, request.body)
                .header("accept-encoding", "gzip")
                .header("user-agent", USER_AGENT)
                .build();
    }

    protected void backoffQueue() {
        long delay = bucket.retryAfter();
        if (delay > 0)
            LOG.debug("Backing off queue for {}", delay);
        pool.schedule(this::drainQueue, delay, TimeUnit.MILLISECONDS);
    }

    protected synchronized void drainQueue() {
        boolean graceful = true;
        while (!queue.isEmpty()) {
            final Request pair = queue.peek();
            graceful = executePair(pair);
            if (!graceful)
                break;
        }
        isQueued = !graceful;
        if (isShutdown && graceful)
            pool.shutdown();
    }

    private boolean enqueuePair(@Async.Schedule Request pair) {
        return queue.add(pair);
    }

    private boolean executePair(@Async.Execute Request req) {
        if (req.future.isDone()) {
            queue.poll();
            return true;
        } else if (req.deadline > 0 && req.deadline < System.currentTimeMillis()) {
            req.future.completeExceptionally(new TimeoutException());
            queue.poll();
            return true;
        }

        final okhttp3.Request request = newRequest(req);
        try (Response response = client.newCall(request).execute()) {
            bucket.update(response);
            if (response.code() == Bucket.RATE_LIMIT_CODE) {
                backoffQueue();
                return false;
            }
            else if (!response.isSuccessful()) {
                final HttpException exception = failure(response);
                LOG.error("Sending a webhook message failed with non-OK http response", exception);
                queue.poll().future.completeExceptionally(exception);
                return true;
            }
            ReadonlyMessage message = null;
            if (parseMessage && !"DELETE".equals(req.method)) {
                InputStream body = IOUtil.getBody(response);
                JSONObject json = IOUtil.toJSON(body);
                message = EntityFactory.makeMessage(json);
            }
            queue.poll().future.complete(message);
            if (bucket.isRateLimit()) {
                backoffQueue();
                return false;
            }
        }
        catch (JSONException | IOException e) {
            LOG.error("There was some error while sending a webhook message", e);
            queue.poll().future.completeExceptionally(e);
        }
        return true;
    }

    enum RequestType {
        SEND("POST"), EDIT("PATCH"), DELETE("DELETE");

        private final String method;

        RequestType(String method) {
            this.method = method;
        }

        public String getMethod() {
            return method;
        }
    }

    protected static final class Bucket {
        public static final int RATE_LIMIT_CODE = 429;
        public long resetTime;
        public int remainingUses;
        public int limit = Integer.MAX_VALUE;

        public synchronized boolean isRateLimit() {
            if (retryAfter() <= 0)
                remainingUses = limit;
            return remainingUses <= 0;
        }

        public synchronized long retryAfter() {
            return resetTime - System.currentTimeMillis();
        }

        private synchronized void handleRatelimit(Response response, long current) throws IOException {
            final String retryAfter = response.header("Retry-After");
            long delay;
            if (retryAfter == null) {
                InputStream stream = IOUtil.getBody(response);
                final JSONObject body = IOUtil.toJSON(stream);
                delay = (long) Math.ceil(body.getDouble("retry_after")) * 1000;
            }
            else {
                delay = Long.parseLong(retryAfter) * 1000;
            }
            LOG.error("Encountered 429, retrying after {}", delay);
            resetTime = current + delay;
        }

        private synchronized void update0(Response response) throws IOException {
            final long current = System.currentTimeMillis();
            final boolean is429 = response.code() == RATE_LIMIT_CODE;
            if (is429) {
                handleRatelimit(response, current);
            }
            else if (!response.isSuccessful()) {
                LOG.debug("Failed to update buckets due to unsuccessful response with code: {} and body: \n{}",
                          response.code(), new IOUtil.Lazy(() -> new String(IOUtil.readAllBytes(IOUtil.getBody(response)))));
                return;
            }
            remainingUses = Integer.parseInt(response.header("X-RateLimit-Remaining","50"));
            limit = Integer.parseInt(response.header("X-RateLimit-Limit","50"));

            if (!is429) {
                final long reset = (long) Math.ceil(Double.parseDouble(response.header("X-RateLimit-Reset-After","60"))); // relative seconds
                final long delay = reset * 1000;
                resetTime = current + delay;
            }
        }

        public void update(Response response) {
            try {
                update0(response);
            }
            catch (Exception ex) {
                LOG.error("Could not read http response", ex);
            }
        }
    }

    private static final class Request {
        private final CompletableFuture<ReadonlyMessage> future;
        private final RequestBody body;
        private final String method, url;
        private long deadline;

        public Request(CompletableFuture<ReadonlyMessage> future, RequestBody body, String method, String url) {
            this.future = future;
            this.body = body;
            this.method = method;
            this.url = url;
        }
    }
}
