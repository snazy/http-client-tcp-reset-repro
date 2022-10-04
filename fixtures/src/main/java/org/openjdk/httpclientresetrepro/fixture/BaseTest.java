package org.openjdk.httpclientresetrepro.fixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.SubmissionPublisher;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.socket.ConnectionSocketFactory;
import org.apache.hc.client5.http.socket.PlainConnectionSocketFactory;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.http.io.entity.HttpEntities;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public abstract class BaseTest {

  public static final int CONNECT_TIMEOUT = 5_000;
  public static final int READ_TIMEOUT = 5_000;

  public static final String CONTENT_TYPE = "application/json";
  public static final String GZIP = "gzip";
  public static final String DEFLATE = "deflate";
  public static final String ACCEPT_ENCODING = GZIP + ";q=1.0, " + DEFLATE + ";q=0.9";
  public static final String HEADER_ACCEPT = "Accept";
  public static final String HEADER_ACCEPT_ENCODING = "Accept-Encoding";
  public static final String HEADER_CONTENT_ENCODING = "Content-Encoding";
  public static final String HEADER_CONTENT_TYPE = "Content-Type";

  protected URI uri;

  protected void requestHandler(String method, IntSupplier length,
      Supplier<InputStream> inputStream, Runnable before, Supplier<OutputStream> outputStream)
      throws IOException {

    System.err.println("REQUEST START: " + method);

    String input;
    switch (method) {
      case "PUT":
      case "POST":
        try (InputStream in = inputStream.get()) {
          input = readAll(in);
        }
        break;
      case "GET":
      case "DELETE":
        input = constructPayload(length.getAsInt());
        break;
      default:
        fail();
        return;
    }

    System.err.println("REQUEST FOO: " + method + " / " + input.length());

    before.run();

    try (OutputStream os = outputStream.get()) {
      writeAll(os, input);
    }

    System.err.println("REQUEST COMPLETE: " + method + " / " + input.length());
  }

  protected static String constructPayload(int len) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < len; i++) {
      sb.append((char) ((i & 63) + 32));
    }
    return sb.toString();
  }

  static Stream<Arguments> requestsAndPayload() {
    return Stream.of("POST", "PUT", "GET", "DELETE")
        .flatMap(m -> IntStream.of(1000, 100_000, 10_000_000).boxed().flatMap(payloadSize -> {
              boolean write = "PUT".equals(m) || "POST".equals(m);
              String extra = write ? "" : ("?" + payloadSize);
              return Stream.of(
                  arguments(m, payloadSize, false, write, extra),
                  arguments(m, payloadSize, true, write, extra)
              );
            }
        ));
  }

  @ParameterizedTest
  @MethodSource("requestsAndPayload")
  protected void withUrlConnection(String method, int payloadSize, boolean withCompression,
      boolean write, String extra)
      throws Exception {
    URI u = uri.resolve(extra);

    HttpURLConnection con = (HttpURLConnection) u.toURL().openConnection();

    con.setReadTimeout(READ_TIMEOUT);
    con.setConnectTimeout(CONNECT_TIMEOUT);

    con.addRequestProperty(HEADER_ACCEPT, CONTENT_TYPE);
    con.addRequestProperty(HEADER_CONTENT_TYPE, CONTENT_TYPE);
    if (withCompression) {
      con.addRequestProperty(HEADER_ACCEPT_ENCODING, ACCEPT_ENCODING);
      if (write) {
        con.addRequestProperty(HEADER_CONTENT_ENCODING, GZIP);
      }
    }

    con.setRequestMethod(method);

    String data = constructPayload(payloadSize);

    if (write) {
      con.setDoOutput(true);
      try (OutputStream out = wrapOutputStream(con.getOutputStream(), withCompression)) {
        writeAll(out, data);
      }
    }

    con.connect();
    con.getResponseCode(); // call to ensure http request is complete

    try (InputStream in = maybeDecompress(con)) {
      String received = readAll(in);
      assertThat(received).isEqualTo(data);
    }
  }

  private static void writeAll(OutputStream out, String data) throws IOException {
    byte[] b = data.getBytes(StandardCharsets.UTF_8);
    for (int p = 0; p < b.length; ) {
      int len = Math.min(1024, b.length - p);
      if (len > 0) {
        out.write(b, p, len);
      }
      p += len;
    }
  }

  private static String readAll(InputStream in) throws IOException {
    // work around AssertionError from JDK-8228970, which wasn't back ported to Java 11
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    byte[] buf = new byte[1024];
    while (true) {
      int rd = in.read(buf);
      if (rd < 0) {
        break;
      }
      if (rd > 0) {
        os.write(buf, 0, rd);
      }
    }
    return os.toString(StandardCharsets.UTF_8);
  }

  protected static InputStream maybeDecompress(HttpURLConnection con) throws Exception {
    String contentEncoding = con.getHeaderField(HEADER_CONTENT_ENCODING);
    if (GZIP.equals(contentEncoding)) {
      return new GZIPInputStream(con.getInputStream());
    } else if (DEFLATE.equals(contentEncoding)) {
      return new InflaterInputStream(con.getInputStream());
    } else {
      return con.getInputStream();
    }
  }

  protected static OutputStream wrapOutputStream(OutputStream base, boolean withCompression)
      throws IOException {
    return withCompression
        ? new GZIPOutputStream(base) : new BufferedOutputStream(base);
  }

  //

  static HttpClient javaHttpClient;

  @BeforeAll
  static void setupJavaHttpClient() {
    javaHttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT)).version(Version.HTTP_1_1).build();
  }

  @ParameterizedTest
  @MethodSource("requestsAndPayload")
  protected void withNewJavaHttpClient(String method, int payloadSize, boolean withCompression,
      boolean write, String extra)
      throws Exception {
    URI u = uri.resolve(extra);

    HttpRequest.Builder request =
        HttpRequest.newBuilder().uri(u).timeout(Duration.ofMillis(READ_TIMEOUT))
            .header(HEADER_ACCEPT, CONTENT_TYPE)
            .header(HEADER_CONTENT_TYPE, CONTENT_TYPE);
    if (withCompression) {
      request.header(HEADER_ACCEPT_ENCODING, ACCEPT_ENCODING);
      if (write) {
        request.header(HEADER_CONTENT_ENCODING, GZIP);
      }
    }

    String data = constructPayload(payloadSize);

    BodyPublisher bodyPublisher =
        write ? BodyPublishers.fromPublisher(publishBody(data, withCompression))
            : BodyPublishers.noBody();

    request.method(method, bodyPublisher);
    HttpResponse<InputStream> response = javaHttpClient.send(request.build(),
        BodyHandlers.ofInputStream());

    try (InputStream in = maybeDecompress(response)) {
      String received = readAll(in);
      assertThat(received).isEqualTo(data);
    }
  }

  private InputStream maybeDecompress(HttpResponse<InputStream> response) throws IOException {
    InputStream inputStream = response.body();
    String contentEncoding = response.headers().firstValue(HEADER_CONTENT_ENCODING).orElse("");
    if (GZIP.equals(contentEncoding)) {
      return new GZIPInputStream(inputStream);
    } else if (DEFLATE.equals(contentEncoding)) {
      return new InflaterInputStream(inputStream);
    } else {
      return inputStream;
    }
  }

  private static Flow.Publisher<ByteBuffer> publishBody(String data, boolean withCompression) {
    return new SubmissionPublisher<>() {
      @Override
      public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
        super.subscribe(subscriber);

        ForkJoinPool.commonPool().submit(() -> {
          try {
            System.err.println("SUBSCRIBE...");
            try (OutputStream out = wrapOutputStream(new SubmittingOutputStream(this),
                withCompression)) {
              writeAll(out, data);
            }
            System.err.println("WRITTEN...");
            close();
            System.err.println("CLOSED...");
          } catch (Exception e) {
            closeExceptionally(e);
          }
        });
      }
    };
  }

  private static final class SubmittingOutputStream extends OutputStream {

    private final SubmissionPublisher<ByteBuffer> submissionPublisher;

    public SubmittingOutputStream(SubmissionPublisher<ByteBuffer> submissionPublisher) {
      this.submissionPublisher = submissionPublisher;
    }

    @Override
    public void write(int b) {
      // this is never called in practice, but better be on the safe side and implement it
      byte[] arr = new byte[]{(byte) b};
      write(arr, 0, 1);
    }

    @Override
    public void write(byte[] b) {
      write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) {
      submissionPublisher.submit(ByteBuffer.wrap(Arrays.copyOfRange(b, off, off + len)));
    }
  }

  //

  static CloseableHttpClient apacheClient;
  static RequestConfig apacheRequestConfig;

  @BeforeAll
  static void setupApacheHttpClient() {
    RegistryBuilder<ConnectionSocketFactory> socketFactoryRegistryBuilder =
        RegistryBuilder.<ConnectionSocketFactory>create()
            .register("http", PlainConnectionSocketFactory.INSTANCE);

    PoolingHttpClientConnectionManager connManager =
        new PoolingHttpClientConnectionManager(
            socketFactoryRegistryBuilder.build(),
            PoolConcurrencyPolicy.STRICT,
            PoolReusePolicy.LIFO,
            TimeValue.ofMinutes(5));

    SocketConfig socketConfig =
        SocketConfig.custom()
            .setTcpNoDelay(true)
            .setSoTimeout(Timeout.ofMilliseconds(READ_TIMEOUT))
            .setTcpNoDelay(true)
            .build();

    connManager.setDefaultSocketConfig(socketConfig);
    connManager.setValidateAfterInactivity(TimeValue.ofSeconds(10));
    connManager.setMaxTotal(100);
    connManager.setDefaultMaxPerRoute(10);

    apacheRequestConfig =
        RequestConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT))
            .setResponseTimeout(Timeout.ofMilliseconds(READ_TIMEOUT))
            .setRedirectsEnabled(true)
            .setCircularRedirectsAllowed(false)
            .setMaxRedirects(5)
            .build();

    HttpClientBuilder clientBuilder =
        HttpClients.custom()
            .disableDefaultUserAgent()
            .disableAuthCaching()
            .disableCookieManagement()
            .setConnectionManager(connManager)
            .setDefaultRequestConfig(apacheRequestConfig);

    apacheClient = clientBuilder.build();
  }

  @ParameterizedTest
  @MethodSource("requestsAndPayload")
  protected void withApacheHttpClient(String method, int payloadSize, boolean withCompression,
      boolean write, String extra)
      throws Exception {
    URI u = uri.resolve(extra);

    HttpUriRequestBase request = new HttpUriRequestBase(method, u);
    request.addHeader(HEADER_ACCEPT, CONTENT_TYPE);
    request.addHeader(HEADER_CONTENT_TYPE, CONTENT_TYPE);
    if (withCompression) {
      request.addHeader(HEADER_ACCEPT_ENCODING, ACCEPT_ENCODING);
    }

    request.setConfig(
        RequestConfig.copy(apacheRequestConfig).setContentCompressionEnabled(withCompression)
            .build());

    String data = constructPayload(payloadSize);

    if (write) {
      HttpEntity entity =
          HttpEntities.create(os -> writeAll(os, data), ContentType.parse(CONTENT_TYPE));
      request.setEntity(entity);
    }

    try (CloseableHttpResponse response = apacheClient.execute(request)) {
      try (InputStream in = response.getEntity().getContent()) {
        String received = readAll(in);
        assertThat(received).isEqualTo(data);
      }
    }
  }
}
