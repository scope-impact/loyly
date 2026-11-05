package platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;

// Proves the offline library set compiles, tests, and packages; not shipped to candidates.
public final class Main {
  public static void main(String[] args) throws Exception {
    byte[] body = new ObjectMapper().writeValueAsBytes(Map.of("ok", true));
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/health", exchange -> {
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
  }
}
