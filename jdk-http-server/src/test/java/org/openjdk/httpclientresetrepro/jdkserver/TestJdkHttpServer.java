package org.openjdk.httpclientresetrepro.jdkserver;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.openjdk.httpclientresetrepro.fixture.BaseTest;

public class TestJdkHttpServer extends BaseTest {

  private HttpServer server;

  @BeforeEach
  void startServer() throws Exception {
    HttpHandler safeHandler =
        exchange -> requestHandler(exchange.getRequestMethod(), () -> {
          String q = exchange.getRequestURI().getQuery();
          return Integer.parseInt(q);
        }, () -> {
          InputStream in = exchange.getRequestBody();
          List<String> contentEncoding = exchange.getRequestHeaders().get(HEADER_CONTENT_ENCODING);
          if (contentEncoding != null && !contentEncoding.isEmpty()) {
            try {
              return new GZIPInputStream(in);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
          return in;
        }, () -> {
          exchange.getResponseHeaders().add(HEADER_CONTENT_TYPE, CONTENT_TYPE);
          List<String> acceptEncoding = exchange.getRequestHeaders()
              .get(HEADER_ACCEPT_ENCODING);
          if (acceptEncoding != null && !acceptEncoding.isEmpty()) {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
          }
          try {
            exchange.sendResponseHeaders(200, 0);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }, () -> {
          OutputStream out = exchange.getResponseBody();
          List<String> acceptEncoding = exchange.getRequestHeaders()
              .get(HEADER_ACCEPT_ENCODING);
          if (acceptEncoding != null && !acceptEncoding.isEmpty()) {
            try {
              out = new GZIPOutputStream(out);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
          return out;
        });
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/", safeHandler);
    server.setExecutor(null);

    server.start();

    InetSocketAddress address = server.getAddress();
    uri = URI.create(
        "http://"
            + address.getAddress().getHostAddress()
            + ":"
            + address.getPort()
            + "/");
  }

  @AfterEach
  void stopServer() {
    try {
      server.stop(0);
    } finally {
      server = null;
    }
  }
}
