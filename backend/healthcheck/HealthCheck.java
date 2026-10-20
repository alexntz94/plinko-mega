import java.net.HttpURLConnection;
import java.net.URI;

/** Container health probe for the distroless image (no shell or curl): exits 0 when the app reports UP. */
public class HealthCheck {
    public static void main(String[] args) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:8080/actuator/health").toURL().openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        System.exit(connection.getResponseCode() == 200 ? 0 : 1);
    }
}
