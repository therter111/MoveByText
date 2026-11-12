package KirizsKeve.MoveByText.nlp;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * One HTTP server a backend talks to. Sends JSON with the JDK's HttpURLConnection only, so there is nothing extra
 * to bundle into the mod jar. A subclass adds what differs between providers: authentication (prepare(), query())
 * and the wording of failures, so the player reads what to check instead of a bare exception.
 */
public abstract class HttpEndpoint {

    /** Without a trailing slash, e.g. "http://gaming-pc:5001". */
    private final String baseUrl;
    private final int connectTimeoutMs;

    protected HttpEndpoint(String baseUrl, int connectTimeoutMs) {
        this.baseUrl = baseUrl;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /**
     * One request: a JSON body when body isn't null. Returns the body of a 200 answer; anything else throws, with
     * the subclass's wording.
     */
    public String send(String method, String path, String body, int readTimeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path + query()).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            prepare(conn);

            int code;
            String responseText;
            try {
                if (body != null) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    OutputStream os = conn.getOutputStream();
                    try {
                        os.write(body.getBytes("UTF-8"));
                    } finally {
                        os.close();
                    }
                }
                code = conn.getResponseCode();
                InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                responseText = readFully(stream);
            } catch (UnknownHostException e) {
                String message = cannotFind();
                if (message == null) throw e;
                throw (UnknownHostException) new UnknownHostException(message).initCause(e);
            } catch (ConnectException e) {
                String message = cannotConnect();
                if (message == null) throw e;
                throw (ConnectException) new ConnectException(message).initCause(e);
            } catch (SocketTimeoutException e) {
                boolean connecting = String.valueOf(e.getMessage()).toLowerCase().contains("connect");
                String message = timedOut(connecting, readTimeoutMs);
                if (message == null) throw e;
                throw (SocketTimeoutException) new SocketTimeoutException(message).initCause(e);
            }

            if (code != 200) {
                throw new RuntimeException(httpError(code, responseText));
            }
            return responseText;
        } finally {
            conn.disconnect();
        }
    }

    /** Headers for every request, e.g. authentication. Headers keep secrets out of URLs and logs. */
    protected void prepare(HttpURLConnection conn) throws Exception {
    }

    /** Added after the path of every request, e.g. "?token=..."; empty by default. */
    protected String query() throws Exception {
        return "";
    }

    /** The message when the host name can't be resolved; null keeps the JDK's exception. */
    protected String cannotFind() {
        return null;
    }

    /** The message when nothing listens at the address; null keeps the JDK's exception. */
    protected String cannotConnect() {
        return null;
    }

    /** The message when connecting or waiting for the answer timed out; null keeps the JDK's exception. */
    protected String timedOut(boolean connecting, int readTimeoutMs) {
        return null;
    }

    /** The message for an answer other than 200. */
    protected abstract String httpError(int code, String responseText);

    private static String readFully(InputStream in) throws Exception {
        if (in == null) return "";
        try {
            // Starsector's script sandbox blocks most java.io classes (e.g. ByteArrayOutputStream),
            // so grow a plain byte[] by hand instead.
            byte[] data = new byte[8192];
            int len = 0;
            int n;
            while ((n = in.read(data, len, data.length - len)) != -1) {
                len += n;
                if (len == data.length) {
                    data = Arrays.copyOf(data, data.length * 2);
                }
            }
            return new String(data, 0, len, "UTF-8");
        } finally {
            in.close();
        }
    }
}
