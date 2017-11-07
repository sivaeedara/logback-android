package ch.qos.logback.classic.net.splunk;

import java.util.List;

/**
 * @brief Splunk http event collector middleware implementation.
 *
 * @details
 * A user application can utilize HttpEventCollectorMiddleware to customize the behavior
 * of sending events to Splunk. A user application plugs middleware components to
 * the HttpEventCollectorSender by calling addMiddleware method.
 *
 * HttpEventCollectorResendMiddleware.java is an example of how middleware can be used.
 */

public class HttpEventCollectorMiddleware {

    private HttpSenderMiddleware httpSenderMiddleware = null;

    /**
     * An interface that describes an abstract events sender working asynchronously.
     */
    public interface IHttpSender {
        public void postEvents(final List<HttpEventCollectorEventInfo> events,
                               IHttpSenderCallback callback);
    }

    /**
     * Callback methods invoked by events sender.
     */
    public interface IHttpSenderCallback {
        public void completed(int statusCode, final String reply);
        public void failed(final Exception ex);
    }

    /**
     * An abstract middleware component.
     */
    public static abstract class HttpSenderMiddleware {
        private HttpSenderMiddleware next;

        public abstract void postEvents(
                final List<HttpEventCollectorEventInfo> events,
                IHttpSender sender,
                IHttpSenderCallback callback);

        protected void callNext(final List<HttpEventCollectorEventInfo> events,
                                IHttpSender sender,
                                IHttpSenderCallback callback) {
            if (next != null) {
                next.postEvents(events, sender, callback);
            } else {
                sender.postEvents(events, callback);
            }
        }
    }

    /**
     * Post http event collector data
     * @param events list
     * @param sender is http sender
     * @param callback async callback
     */
    public void postEvents(final List<HttpEventCollectorEventInfo> events,
                           IHttpSender sender,
                           IHttpSenderCallback callback) {
        if (httpSenderMiddleware == null) {
            sender.postEvents(events, callback);
        } else {
            httpSenderMiddleware.postEvents(events, sender, callback);
        }
    }

    /**
     * Plug a middleware component to the middleware chain.
     * @param middleware is a new middleware
     */
    public void add(final HttpSenderMiddleware middleware) {
        if (httpSenderMiddleware == null) {
            httpSenderMiddleware = middleware;
        } else {
            middleware.next = httpSenderMiddleware;
            httpSenderMiddleware = middleware;
        }
    }
}
