package ch.qos.logback.classic.net.splunk;


import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import java.util.List;

/**
 * @brief Splunk http event collector error handler.
 *
 * @details
 * A user application can utilize HttpEventCollectorErrorHandler in order to detect errors
 * caused by network connection and/or Splunk server.
 *
 * Usage example:
 * HttpEventCollectorErrorHandler.onError(new HttpEventErrorHandler.ErrorCallback() {
 *     public void error(final String data, final Exception ex) {  handle exception  }
 * });
 */
public class HttpEventCollectorErrorHandler {

    /**
     * This exception is passed to error callback when Splunk server replies an error
     */
    @SuppressWarnings("serial")
    public static class ServerErrorException extends Exception {
        private String reply;
        private long errorCode = -1;
        private String errorText = "unknown error";

        /**
         * Create an exception with server error reply
         * @param serverReply
         */
        public ServerErrorException(final String serverReply) {
            reply = serverReply;
            JSONParser jsonParser = new JSONParser();
            try {
                // read server reply
                JSONObject json = (JSONObject)jsonParser.parse(serverReply);
                errorCode = (Long)json.get("code");
                errorText = (String)json.get("text");
            } catch (Exception e) {
                errorText = e.getMessage();
            }
        }

        /**
         * @return Splunk server reply in json format
         */
        public String getReply() {
            return reply;
        }

        /**
         * @return error code replied by Splunk server
         */
        public long getErrorCode() {
            return errorCode;
        }

        /**
         * * @return error text replied by Splunk server
         */
        public String getErrorText() {
            return errorText;
        }

        @Override public String toString() {
            return getReply();
        }
    }

    public interface ErrorCallback {
        void error(final List<HttpEventCollectorEventInfo> data, final Exception ex);
    }

    private static ErrorCallback errorCallback;

    /**
     * Register error callbacks
     * @param callback
     */
    public static void onError(ErrorCallback callback) {
        errorCallback = callback;
    }

    /**
     * Report an exception
     * @param data
     * @param ex is an exception thrown by posting or processing data
     */
    public static void error(final List<HttpEventCollectorEventInfo> data, final Exception ex) {
        if (errorCallback != null) {
            errorCallback.error(data, ex);
        }
    }
}