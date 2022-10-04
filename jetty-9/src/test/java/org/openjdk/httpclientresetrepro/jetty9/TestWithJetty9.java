package org.openjdk.httpclientresetrepro.jetty9;

import static org.eclipse.jetty.server.CustomRequestLog.NCSA_FORMAT;

import java.io.IOException;
import java.net.URI;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.Slf4jRequestLogWriter;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.openjdk.httpclientresetrepro.fixture.BaseTest;

public class TestWithJetty9 extends BaseTest {

  private Server server;

  @BeforeEach
  void startServer() throws Exception {
    server = new Server(0);
    AbstractHandler requestHandler =
        new AbstractHandler() {
          @Override
          public void handle(
              String target,
              Request baseRequest,
              HttpServletRequest request,
              HttpServletResponse response)
              throws IOException {
            try {
              requestHandler(request.getMethod(), () -> {
                    String q = request.getQueryString();
                    return Integer.parseInt(q);
                  },
                  () -> {
                    try {
                      return request.getInputStream();
                    } catch (IOException e) {
                      throw new RuntimeException(e);
                    }
                  },
                  () -> {
                    response.setStatus(200);
                    response.setContentType("application/octet-stream");
                  }
                  , () -> {
                    try {
                      return response.getOutputStream();
                    } catch (IOException e) {
                      throw new RuntimeException(e);
                    }
                  });
            } catch (RuntimeException e) {
              e.printStackTrace();
              throw e;
            }
          }
        };

    GzipHandler gzip = new GzipHandler();
    gzip.setInflateBufferSize(8192);
    gzip.addIncludedMethods("PUT");
    server.setHandler(requestHandler);
    server.insertHandler(gzip);
    server.setRequestLog(
        new CustomRequestLog(
            new Slf4jRequestLogWriter(),
            "%{local}a:%{local}p - %{remote}a:%{remote}p - " + NCSA_FORMAT));

    server.start();

    uri = URI.create("http://localhost:" + server.getURI().getPort() + "/");
  }

  @AfterEach
  void stopServer() throws Exception {
    try {
      server.stop();
    } finally {
      server = null;
    }
  }
}
