package ca.floo.roadtrip;

import java.net.HttpURLConnection;
import java.net.URI;

public final class HealthProbe {
    private static final String DEFAULT_ENDPOINT = "http://127.0.0.1:8765/api/health";
    private static final int CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final int READ_TIMEOUT_MILLIS = 2_000;
    private static final int SUCCESS_STATUS_MIN = 200;
    private static final int SUCCESS_STATUS_MAX_EXCLUSIVE = 300;

    private HealthProbe() {
    }

    public static void main(String[] args) {
        String endpoint = args.length == 0 ? DEFAULT_ENDPOINT : args[0];
        int status = statusCode(endpoint);
        if (status < SUCCESS_STATUS_MIN || status >= SUCCESS_STATUS_MAX_EXCLUSIVE) {
            System.err.println("Health probe returned HTTP " + status);
            System.exit(1);
        }
    }

    private static int statusCode(String endpoint) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(endpoint).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            return connection.getResponseCode();
        } catch (Exception e) {
            System.err.println("Health probe failed: " + e.getMessage());
            System.exit(1);
            throw new IllegalStateException(e);
        }
    }
}
