package ch.qos.logback.classic.net.splunk;

import java.util.List;

/**
 * @brief Splunk http event collector resend middleware.
 *
 * @details
 * HTTP event collector middleware plug in that implements a simple resend policy.
 * When HTTP post reply isn't an application error it tries to resend the data.
 * An exponentially growing delay is used to prevent server overflow.
 */
public class HttpEventCollectorResendMiddleware
        extends HttpEventCollectorMiddleware.HttpSenderMiddleware {
    private long retriesOnError = 0;

    /**
     * Create a resend middleware component.
     * @param retriesOnError is the max retry count.
     */
    public HttpEventCollectorResendMiddleware(long retriesOnError) {
        this.retriesOnError = retriesOnError;
    }

    public void postEvents(
            final List<HttpEventCollectorEventInfo> events,
            HttpEventCollectorMiddleware.IHttpSender sender,
            HttpEventCollectorMiddleware.IHttpSenderCallback callback) {
        callNext(events, sender, new Callback(events, sender, callback));
    }

    private class Callback implements HttpEventCollectorMiddleware.IHttpSenderCallback {
        private long retries = 0;
        private final List<HttpEventCollectorEventInfo> events;
        private HttpEventCollectorMiddleware.IHttpSenderCallback prevCallback;
        private HttpEventCollectorMiddleware.IHttpSender sender;
        private final long RetryDelayCeiling = 60 * 1000; // 1 minute
        private long retryDelay = 1000; // start with 1 second

        public Callback(
                final List<HttpEventCollectorEventInfo> events,
                HttpEventCollectorMiddleware.IHttpSender sender,
                HttpEventCollectorMiddleware.IHttpSenderCallback prevCallback) {
            this.events = events;
            this.prevCallback = prevCallback;
            this.sender = sender;
        }

        public void completed(int statusCode, final String reply) {
            if (statusCode != 200) {
                // resend wouldn't help - report error
                prevCallback.failed(new HttpEventCollectorErrorHandler.ServerErrorException(reply));
            }
        }

        public void failed(final Exception ex) {
            if (retries < retriesOnError) {
                retries++;
                try {
                    Thread.sleep(retryDelay);
                    callNext(events, sender, this);
                } catch (InterruptedException ie) {
                    prevCallback.failed(ie);
                }
                // increase delay exponentially
                retryDelay = Math.min(RetryDelayCeiling, retryDelay * 2);
            } else {
                prevCallback.failed(ex);
            }
        }
    }
}