package ch.qos.logback.classic.net.splunk;

import okhttp3.*;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;

import org.json.simple.JSONObject;

import javax.net.ssl.*;
import java.io.IOException;
import java.io.Serializable;
import java.security.cert.CertificateException;
import java.util.Dictionary;
import java.util.Timer;
import java.util.TimerTask;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.Locale;


/**
 * Created by seedara on 11/7/17.
 */
final class HttpEventCollectorSender extends TimerTask implements HttpEventCollectorMiddleware.IHttpSender {
    public static final String MetadataTimeTag = "time";
    public static final String MetadataHostTag = "host";
    public static final String MetadataIndexTag = "index";
    public static final String MetadataSourceTag = "source";
    public static final String MetadataSourceTypeTag = "sourcetype";
    private static final String AuthorizationHeaderTag = "Authorization";
    private static final String AuthorizationHeaderScheme = "Splunk %s";
    private static final String HttpEventCollectorUriPath = "/services/collector";
    private static final String HttpContentType = "application/json; profile=urn:splunk:event:1.0; charset=utf-8";
    private static final String SendModeSequential = "sequential";
    private static final String SendModeSParallel = "parallel";

    /**
     * Sender operation mode. Parallel means that all HTTP requests are
     * asynchronous and may be indexed out of order. Sequential mode guarantees
     * sequential order of the indexed events.
     */
    public enum SendMode
    {
        Sequential,
        Parallel
    };

    /**
     * Recommended default values for events batching.
     */
    public static final int DefaultBatchInterval = 10 * 1000; // 10 seconds
    public static final int DefaultBatchSize = 10 * 1024; // 10KB
    public static final int DefaultBatchCount = 10; // 10 events

    private String url;
    private String token;
    private long maxEventsBatchCount;
    private long maxEventsBatchSize;
    private Dictionary<String, String> metadata;
    private Timer timer;
    private List<HttpEventCollectorEventInfo> eventsBatch = new LinkedList<HttpEventCollectorEventInfo>();
    private long eventsBatchSize = 0; // estimated total size of events batch
    private OkHttpClient httpClient;
    private boolean disableCertificateValidation = false;
    private SendMode sendMode = SendMode.Sequential;
    private HttpEventCollectorMiddleware middleware = new HttpEventCollectorMiddleware();
    public static final MediaType JSON
            = MediaType.parse("application/json; charset=utf-8");
    /**
     * Initialize HttpEventCollectorSender
     * @param Url http event collector input server
     * @param token application token
     * @param delay batching delay
     * @param maxEventsBatchCount max number of events in a batch
     * @param maxEventsBatchSize max size of batch
     * @param metadata events metadata
     */
    public HttpEventCollectorSender(
            final String Url, final String token,
            long delay, long maxEventsBatchCount, long maxEventsBatchSize,
            String sendModeStr,
            Dictionary<String, String> metadata) {
        this.url = Url + HttpEventCollectorUriPath;
        this.token = token;
        // when size configuration setting is missing it's treated as "infinity",
        // i.e., any value is accepted.
        if (maxEventsBatchCount == 0 && maxEventsBatchSize > 0) {
            maxEventsBatchCount = Long.MAX_VALUE;
        } else if (maxEventsBatchSize == 0 && maxEventsBatchCount > 0) {
            maxEventsBatchSize = Long.MAX_VALUE;
        }
        this.maxEventsBatchCount = maxEventsBatchCount;
        this.maxEventsBatchSize = maxEventsBatchSize;
        this.metadata = metadata;
        if (sendModeStr != null) {
            if (sendModeStr.equals(SendModeSequential))
                this.sendMode = SendMode.Sequential;
            else if (sendModeStr.equals(SendModeSParallel))
                this.sendMode = SendMode.Parallel;
            else
                throw new IllegalArgumentException("Unknown send mode: " + sendModeStr);
        }

        if (delay > 0) {
            // start heartbeat timer
            timer = new Timer();
            timer.scheduleAtFixedRate(this, delay, delay);
        }
    }

    public void addMiddleware(HttpEventCollectorMiddleware.HttpSenderMiddleware middleware) {
        this.middleware.add(middleware);
    }

    /**
     * Send a single logging event
     * @note in case of batching the event isn't sent immediately
     * @param severity event severity level (info, warning, etc.)
     * @param message event text
     */
    public synchronized void send(
            final String severity,
            final String message,
            final String logger_name,
            final String thread_name,
            Map<String, String> properties,
            final String exception_message,
            Serializable marker
    ) {
        // create event info container and add it to the batch
        HttpEventCollectorEventInfo eventInfo =
                new HttpEventCollectorEventInfo(severity, message, logger_name, thread_name, properties, exception_message, marker);
        eventsBatch.add(eventInfo);
        eventsBatchSize += severity.length() + message.length();
        if (eventsBatch.size() >= maxEventsBatchCount || eventsBatchSize > maxEventsBatchSize) {
            flush();
        }
    }

    /**
     * Send a single logging event with message only
     * @note in case of batching the event isn't sent immediately
     * @param message event text
     */
    public synchronized void send(final String message) {
        send("", message, "", "", null, null, "");
    }

    /**
     * Flush all pending events
     */
    public synchronized void flush() {
        if (eventsBatch.size() > 0) {
            postEventsAsync(eventsBatch);
        }
        // Clear the batch. A new list should be created because events are
        // sending asynchronously and "previous" instance of eventsBatch object
        // is still in use.
        eventsBatch = new LinkedList<HttpEventCollectorEventInfo>();
        eventsBatchSize = 0;
    }

    /**
     * Close events sender
     */
    public void close() {
        if (timer != null)
            timer.cancel();
        flush();
    }

    /**
     * Timer heartbeat
     */
    @Override // TimerTask
    public void run() {
        flush();
    }

    /**
     * Disable https certificate validation of the splunk server.
     * This functionality is for development purpose only.
     */
    public void disableCertificateValidation() {
        disableCertificateValidation = true;
    }

    @SuppressWarnings("unchecked")
    private static void putIfPresent(JSONObject collection, String tag, String value) {
        if (value != null && value.length() > 0) {
            collection.put(tag, value);
        }
    }

    @SuppressWarnings("unchecked")
    private String serializeEventInfo(HttpEventCollectorEventInfo eventInfo) {
        // create event json content
        //
        // cf: http://dev.splunk.com/view/event-collector/SP-CAAAE6P
        //
        JSONObject event = new JSONObject();
        // event timestamp and metadata
        putIfPresent(event, MetadataTimeTag, String.format(Locale.US, "%.3f", eventInfo.getTime()));
        putIfPresent(event, MetadataHostTag, metadata.get(MetadataHostTag));
        putIfPresent(event, MetadataIndexTag, metadata.get(MetadataIndexTag));
        putIfPresent(event, MetadataSourceTag, metadata.get(MetadataSourceTag));
        putIfPresent(event, MetadataSourceTypeTag, metadata.get(MetadataSourceTypeTag));
        // event body
        JSONObject body = new JSONObject();
        putIfPresent(body, "severity", eventInfo.getSeverity());
        putIfPresent(body, "message", eventInfo.getMessage());
        putIfPresent(body, "logger", eventInfo.getLoggerName());
        putIfPresent(body, "thread", eventInfo.getThreadName());
        // add an exception record if and only if there is one
        // in practice, the message also has the exception information attached
        if (eventInfo.getExceptionMessage() != null) {
            putIfPresent(body, "exception", eventInfo.getExceptionMessage());
        }

        // add properties if and only if there are any
        final Map<String,String> props = eventInfo.getProperties();
        if (props != null && !props.isEmpty()) {
            body.put("properties", props);
        }
        // add marker if and only if there is one
        final Serializable marker = eventInfo.getMarker();
        if (marker != null) {
            putIfPresent(body, "marker", marker.toString());
        }
        // join event and body
        event.put("event", body);
        return event.toString();
    }

    private void startHttpClient() {
        if (httpClient != null) {
            // http client is already started
            return;
        }
        // limit max  number of async requests in sequential mode, 0 means "use
        // default limit"
        int maxConnTotal = sendMode == SendMode.Sequential ? 1 : 0;
        if (! disableCertificateValidation) {
            // create an http client that validates certificates
//            httpClient = HttpAsyncClients.custom()
//                    .setMaxConnTotal(maxConnTotal)
//                    .build();

            httpClient = new OkHttpClient();

        } else {
//            // create strategy that accepts all certificates
//            TrustStrategy acceptingTrustStrategy = new TrustStrategy() {
//                public boolean isTrusted(X509Certificate[] certificate,
//                                         String type) {
//                    return true;
//                }
//            };
//            SSLContext sslContext = null;
//            try {
//                sslContext = SSLContexts.custom().loadTrustMaterial(
//                        null, acceptingTrustStrategy).build();
//                httpClient = HttpAsyncClients.custom()
//                        .setMaxConnTotal(maxConnTotal)
//                        .setHostnameVerifier(SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER)
//                        .setSSLContext(sslContext)
//                        .build();
//            } catch (Exception e) { }

            try {
                // Create a trust manager that does not validate certificate chains
                final TrustManager[] trustAllCerts = new TrustManager[] {
                        new X509TrustManager() {
                            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                            }

                            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                            }

                            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                                return new java.security.cert.X509Certificate[]{};
                            }
                        }
                };

                // Install the all-trusting trust manager
                final SSLContext sslContext = SSLContext.getInstance("SSL");
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                // Create an ssl socket factory with our all-trusting manager
                final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

                OkHttpClient.Builder builder = new OkHttpClient.Builder();
                builder.sslSocketFactory(sslSocketFactory, (X509TrustManager)trustAllCerts[0]);
                builder.hostnameVerifier(new HostnameVerifier() {
                    public boolean verify(String hostname, SSLSession session) {
                        return true;
                    }
                });

                httpClient = builder.build();

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
       // httpClient.start();
    }

    // Currently we never close http client. This method is added for symmetry
    // with startHttpClient.
    private void stopHttpClient() throws SecurityException {
        if (httpClient != null) {
//            try {
//                //httpClient..close();
//            } catch (IOException e) {}
            httpClient = null;
        }
    }

    private void postEventsAsync(final List<HttpEventCollectorEventInfo> events) {
        this.middleware.postEvents(events, this, new HttpEventCollectorMiddleware.IHttpSenderCallback() {
            public void completed(int statusCode, String reply) {
                if (statusCode != 200) {
                    HttpEventCollectorErrorHandler.error(
                            events,
                            new HttpEventCollectorErrorHandler.ServerErrorException(reply));
                }
            }

            public void failed(Exception ex) {
                HttpEventCollectorErrorHandler.error(
                        eventsBatch,
                        new HttpEventCollectorErrorHandler.ServerErrorException(ex.getMessage()));
            }
        });
    }

    public void postEvents(final List<HttpEventCollectorEventInfo> events,
                           final HttpEventCollectorMiddleware.IHttpSenderCallback callback) {
        startHttpClient(); // make sure http client is started
        final String encoding = "utf-8";
        // convert events list into a string
        StringBuilder eventsBatchString = new StringBuilder();
        for (HttpEventCollectorEventInfo eventInfo : events)
            eventsBatchString.append(serializeEventInfo(eventInfo));
        // create http request
//        final HttpPost httpPost = new HttpPost(url);
//        httpPost.setHeader(
//                AuthorizationHeaderTag,
//                String.format(AuthorizationHeaderScheme, token));
//        StringEntity entity = null;
//            entity = new StringEntity(eventsBatchString.toString(), encoding);
//        entity.setContentType(HttpContentType);
//        httpPost.setEntity(entity);

        RequestBody body = RequestBody.create(JSON, eventsBatchString.toString());
        Request request = new Request.Builder()
                .url(url)
                .addHeader(AuthorizationHeaderTag,String.format(AuthorizationHeaderScheme, token))
                .addHeader("Content-Type", HttpContentType)
                .post(body)
                .build();


        httpClient.newCall(request).enqueue(new Callback() {
            public void onFailure(Call call, IOException e) {
                callback.failed(e);
            }

            public void onResponse(Call call, Response response) throws IOException {
                String reply = "";
                int httpStatusCode = response.code();
                if (httpStatusCode != 200) {
                    try {
                        reply = response.body().string();
                    } catch (Exception e) {
                        reply = e.getMessage();
                    }
                }
                callback.completed(httpStatusCode, reply);
            }
        });
//        httpClient.execute(httpPost, new FutureCallback<HttpResponse>() {
//            public void completed(HttpResponse response) {
//                String reply = "";
//                int httpStatusCode = response.getStatusLine().getStatusCode();
//                // read reply only in case of a server error
//                if (httpStatusCode != 200) {
//                    try {
//                        reply = EntityUtils.toString(response.getEntity(), encoding);
//                    } catch (IOException e) {
//                        reply = e.getMessage();
//                    }
//                }
//                callback.completed(httpStatusCode, reply);
//            }
//
//            public void failed(Exception ex) {
//                callback.failed(ex);
//            }
//
//            public void cancelled() {}
//        });
    }
}