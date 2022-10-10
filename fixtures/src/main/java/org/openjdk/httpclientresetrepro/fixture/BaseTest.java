package org.openjdk.httpclientresetrepro.fixture;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.junit.jupiter.params.provider.ValueSource;

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
  public static final String[] METHODS = {"GET", "DELETE", "PUT", "POST"};

  protected URI uri;

  //

  private volatile Runnable next;

  protected void requestHandler(String method, IntSupplier length,
      Supplier<InputStream> inputStream, Runnable before, Supplier<OutputStream> outputStream)
      throws IOException {

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

    before.run();

    try (OutputStream os = outputStream.get()) {
      writeAll(os, input);
      if (next != null) {
        Runnable r = next;
        next = null;
        r.run();
      }
    }
  }

  protected static String constructPayload(int len) {
    StringBuilder sb = new StringBuilder();
    ThreadLocalRandom r = ThreadLocalRandom.current();
    for (int i = 0; i < len; i++) {
      sb.append((char) (r.nextInt(63) + 32));
    }
    return sb.toString();
  }

  static Stream<Arguments> requestsAndPayload() {
    return IntStream.of(1000, 100_000, 1_000_000).boxed()
        .flatMap(payloadSize ->
            Stream.of("GET", "DELETE", "PUT", "POST").flatMap(m -> {
                  boolean write = isWrite(m);
                  String extra = write ? "" : ("?" + payloadSize);
                  return Stream.of(
                      arguments(m, payloadSize, false, write, extra),
                      arguments(m, payloadSize, true, write, extra)
                  );
                }
            ));
  }

  static Stream<Arguments> compressionAndPayload() {
    return Stream.of(false, true).flatMap(compress ->
        IntStream.of(1000, 100_000, 1_000_000).boxed()
            .map(payloadSize -> arguments(payloadSize, compress)));
  }

  private static boolean isWrite(String method) {
    return "PUT".equals(method) || "POST".equals(method);
  }

  //

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 5})
  public void chunkedEagerRequests(int repetitions) throws Exception {
    byte[] uncompressedPayload = constructPayload(131072).getBytes(US_ASCII);

    String request = String.join("\r\n",
        "PUT / HTTP/1.1",
        "Content-Type: " + CONTENT_TYPE,
        "Host: " + uri.getHost() + ":" + uri.getPort(),
        "Transfer-Encoding: chunked",
        "Content-Encoding: gzip",
        "Accept-Encoding: " + ACCEPT_ENCODING,
        "Connection: keep-alive",
        "",
        ""
    );

    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    try (GZIPOutputStream gzout = new GZIPOutputStream(compressed)) {
      gzout.write(uncompressedPayload);
    }
    byte[] compressedPayload = compressed.toByteArray();

    ByteArrayOutputStream requestData = new ByteArrayOutputStream();
    requestData.write(request.getBytes(US_ASCII));
    requestData.write((Integer.toString(compressedPayload.length, 16) + "\r\n").getBytes(US_ASCII));
    requestData.write(compressedPayload);
    requestData.write("\r\n0\r\n\r\n".getBytes(US_ASCII));

    try (Socket socket = new Socket(uri.getHost(), uri.getPort())) {
      socket.setSendBufferSize(16384);
      socket.setReceiveBufferSize(16384);
      socket.setTcpNoDelay(true);
      AtomicInteger remaining = new AtomicInteger(repetitions);
      try (OutputStream out = socket.getOutputStream();
          InputStream in = socket.getInputStream()) {
        Runnable writeRequest = new Runnable() {
          @Override
          public void run() {
            if (remaining.decrementAndGet() > 0) {
              next = this;
            }
            try {
              out.write(requestData.toByteArray());
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        };

        writeRequest.run();

        for (int i = 0; i < repetitions; i++) {
          Map<String, List<String>> headers = new LinkedHashMap<>();
          String status = readLine(in);
          assertThat(status).startsWith("HTTP/1.1 200 ");

          while (true) {
            String ln = readLine(in);
            if (ln.isEmpty()) {
              break;
            }
            int idx = ln.indexOf(": ");
            if (idx == -1) {
              throw new IllegalStateException("Illegal header line: " + ln);
            }
            String key = ln.substring(0, idx).toLowerCase(Locale.ROOT);
            String value = ln.substring(idx + 2);
            headers.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
          }

          byte[] responseData = null;
          if (headers.containsKey("content-length")) {
            int contentLength = Integer.parseInt(headers.get("content-length").get(0));
            responseData = in.readNBytes(contentLength);
          } else if (headers.containsKey("transfer-encoding")) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            while (true) {
              String chunk = readLine(in);
              int chunkLen = Integer.parseInt(chunk, 16);

              if (chunkLen == 0) {
                if (!readLine(in).isEmpty()) {
                  throw new IllegalStateException("Illegal empty chunk trailer");
                }
                break;
              }

              buffer.write(in.readNBytes(chunkLen));

              if (!readLine(in).isEmpty()) {
                throw new IllegalStateException("Illegal empty chunk trailer");
              }
            }
            responseData = buffer.toByteArray();
          }

          if (headers.containsKey("content-encoding")) {
            ByteArrayOutputStream uncompressed = new ByteArrayOutputStream();
            try (InputStream c = new GZIPInputStream(new ByteArrayInputStream(responseData))) {
              c.transferTo(uncompressed);
            }
            responseData = uncompressed.toByteArray();
          }

          assertThat(responseData).isEqualTo(uncompressedPayload);
        }
      }
    }
  }

  String readLine(InputStream in) throws Exception {
    StringBuilder sb = new StringBuilder();
    while (true) {
      int ch = in.read();
      if (ch == -1) {
        throw new EOFException();
      }
      if (ch == 13) {
        ch = in.read();
        if (ch == 10) {
          return sb.toString();
        }
        throw new IllegalStateException(
            "Got invalid character " + ch + " after CR, line so far: '" + sb + "'");
      }
      sb.append((char) ch);
    }
  }

  //

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

    String data = write ? constructPayload(payloadSize) : null;

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
      if (data != null) {
        assertThat(received).isEqualTo(data);
      } else {
        assertThat(received).hasSize(payloadSize);
      }
    }
  }

  private static void writeAll(OutputStream out, String data) throws IOException {
    byte[] b = data.getBytes(StandardCharsets.UTF_8);
    for (int p = 0; p < b.length; ) {
      int len = Math.min(8000, b.length - p);
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
  @MethodSource("compressionAndPayload")
  protected void withNewJavaHttpClient(int payloadSize, boolean withCompression)
      throws Exception {
    URI uriExtra = uri.resolve("?" + payloadSize);
    String data = constructPayload(payloadSize);

    BlockingQueue<HttpResponse<InputStream>> responses = new ArrayBlockingQueue<>(10);

    int repetitions = 10;
    Semaphore blocker = new Semaphore(1);

    Thread submitter = new Thread(() -> {
      for (int i = 0; i < repetitions; i++) {
        try {
          for (String method : METHODS) {
            blocker.acquire();

            next = blocker::release;

            boolean write = isWrite(method);

            Builder request =
                HttpRequest.newBuilder().uri(write ? uri: uriExtra)
                    .timeout(Duration.ofMillis(READ_TIMEOUT))
                    .header(HEADER_ACCEPT, CONTENT_TYPE)
                    .header(HEADER_CONTENT_TYPE, CONTENT_TYPE);
            if (withCompression) {
              request.header(HEADER_ACCEPT_ENCODING, ACCEPT_ENCODING);
              if (write) {
                request.header(HEADER_CONTENT_ENCODING, GZIP);
              }
            }

            BodyPublisher bodyPublisher =
                write ? BodyPublishers.fromPublisher(publishBody(data, withCompression))
                    : BodyPublishers.noBody();

            request.method(method, bodyPublisher);
            HttpResponse<InputStream> response = javaHttpClient.send(request.build(),
                BodyHandlers.ofInputStream());

            responses.put(response);
          }
        } catch (Exception e) {
          e.printStackTrace();
          throw new RuntimeException(e);
        }
      }
    });
    submitter.start();

    for (int i = 0; i < repetitions; i++) {
      for (String method : METHODS) {
        HttpResponse<InputStream> response = responses.take();
        try (InputStream in = maybeDecompress(response)) {
          String received = readAll(in);
          boolean write = isWrite(method);
          if (write) {
            assertThat(received).isEqualTo(data);
          } else {
            assertThat(received).hasSize(payloadSize);
          }
        }
      }
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
            try (OutputStream out = wrapOutputStream(new SubmittingOutputStream(this),
                withCompression)) {
              writeAll(out, data);
            }
            close();
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
    String data = write ? constructPayload(payloadSize) : null;

    HttpUriRequestBase request = new HttpUriRequestBase(method, u);
    request.addHeader(HEADER_ACCEPT, CONTENT_TYPE);
    request.addHeader(HEADER_CONTENT_TYPE, CONTENT_TYPE);
    if (withCompression) {
      request.addHeader(HEADER_ACCEPT_ENCODING, ACCEPT_ENCODING);
    }

    request.setConfig(
        RequestConfig.copy(apacheRequestConfig).setContentCompressionEnabled(withCompression)
            .build());

    if (write) {
      HttpEntity entity =
          HttpEntities.create(os -> writeAll(os, data), ContentType.parse(CONTENT_TYPE));
      request.setEntity(entity);
    }

    try (CloseableHttpResponse response = apacheClient.execute(request)) {
      try (InputStream in = response.getEntity().getContent()) {
        String received = readAll(in);
        if (data != null) {
          assertThat(received).isEqualTo(data);
        } else {
          assertThat(received).hasSize(payloadSize);
        }
      }
    }
  }
}
