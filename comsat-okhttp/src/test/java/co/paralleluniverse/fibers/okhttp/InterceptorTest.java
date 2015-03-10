/*
 * COMSAT
 * Copyright (c) 2013-2015, Parallel Universe Software Co. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
/*
 * Based on the corresponding class in okhttp-tests.
 * Copyright 2014 Square, Inc.
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package co.paralleluniverse.fibers.okhttp;

import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.SuspendExecution;
import com.squareup.okhttp.Address;
import com.squareup.okhttp.Call;
import com.squareup.okhttp.Connection;
import com.squareup.okhttp.Interceptor;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.Protocol;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseBody;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import com.squareup.okhttp.mockwebserver.rule.MockWebServerRule;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import okio.Buffer;
import okio.BufferedSink;
import okio.ForwardingSink;
import okio.ForwardingSource;
import okio.GzipSink;
import okio.Okio;
import okio.Sink;
import okio.Source;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

public final class InterceptorTest {
  @Rule public MockWebServerRule server = new MockWebServerRule();

  private FiberOkHttpClient client = new FiberOkHttpClient();
  private RecordingCallback callback = new RecordingCallback();

  @Test public void applicationInterceptorsCanShortCircuitResponses() throws Exception {
    server.get().shutdown(); // Accept no connections.

    Request request = new Request.Builder()
        .url("https://localhost:1/")
        .build();

    final Response interceptorResponse = new Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("Intercepted!")
        .body(ResponseBody.create(MediaType.parse("text/plain; charset=utf-8"), "abc"))
        .build();

    client.interceptors().add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        return interceptorResponse;
      }
    });

    Response response = executeSynchronously(request);
    assertSame(interceptorResponse, response);
  }

// TODO @Test https://github.com/square/okhttp/issues/1482
  public void networkInterceptorsCannotShortCircuitResponses() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(500));

    Interceptor interceptor = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        return new Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("Intercepted!")
            .body(ResponseBody.create(MediaType.parse("text/plain; charset=utf-8"), "abc"))
            .build();
      }
    };
    client.networkInterceptors().add(interceptor);

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();

    try {
      executeSynchronously(request);
      fail();
    } catch (IllegalStateException expected) {
      assertEquals("network interceptor " + interceptor + " must call proceed() exactly once",
          expected.getMessage());
    }
  }

// TODO @Test https://github.com/square/okhttp/issues/1482
  public void networkInterceptorsCannotCallProceedMultipleTimes() throws Exception {
    server.enqueue(new MockResponse());
    server.enqueue(new MockResponse());

    Interceptor interceptor = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        chain.proceed(chain.request());
        return chain.proceed(chain.request());
      }
    };
    client.networkInterceptors().add(interceptor);

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();

    try {
      executeSynchronously(request);
      fail();
    } catch (IllegalStateException expected) {
      assertEquals("network interceptor " + interceptor + " must call proceed() exactly once",
          expected.getMessage());
    }
  }

// TODO @Test https://github.com/square/okhttp/issues/1482
  public void networkInterceptorsCannotChangeServerAddress() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(500));

    Interceptor interceptor = new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Address address = chain.connection().getRoute().getAddress();
        String sameHost = address.getUriHost();
        int differentPort = address.getUriPort() + 1;
        return chain.proceed(chain.request().newBuilder()
            .url(new URL("http://" + sameHost + ":" + differentPort + "/"))
            .build());
      }
    };
    client.networkInterceptors().add(interceptor);

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();

    try {
      executeSynchronously(request);
      fail();
    } catch (IllegalStateException expected) {
      assertEquals("network interceptor " + interceptor + " must retain the same host and port",
          expected.getMessage());
    }
  }

  @Test public void networkInterceptorsHaveConnectionAccess() throws Exception {
    server.enqueue(new MockResponse());

    client.networkInterceptors().add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Connection connection = chain.connection();
        assertNotNull(connection);
        return chain.proceed(chain.request());
      }
    });

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    executeSynchronously(request);
  }

  @Test public void networkInterceptorsObserveNetworkHeaders() throws Exception {
    server.enqueue(new MockResponse()
        .setBody(gzip("abcabcabc"))
        .addHeader("Content-Encoding: gzip"));

    client.networkInterceptors().add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        // The network request has everything: User-Agent, Host, Accept-Encoding.
        Request networkRequest = chain.request();
        assertNotNull(networkRequest.header("User-Agent"));
        assertEquals(server.get().getHostName() + ":" + server.get().getPort(),
            networkRequest.header("Host"));
        assertNotNull(networkRequest.header("Accept-Encoding"));

        // The network response also has everything, including the raw gzipped content.
        Response networkResponse = chain.proceed(networkRequest);
        assertEquals("gzip", networkResponse.header("Content-Encoding"));
        return networkResponse;
      }
    });

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();

    // No extra headers in the application's request.
    assertNull(request.header("User-Agent"));
    assertNull(request.header("Host"));
    assertNull(request.header("Accept-Encoding"));

    // No extra headers in the application's response.
    Response response = executeSynchronously(request);
    assertNull(request.header("Content-Encoding"));
    assertEquals("abcabcabc", response.body().string());
  }

  @Test public void applicationInterceptorsRewriteRequestToServer() throws Exception {
    rewriteRequestToServer(client.interceptors());
  }

  @Test public void networkInterceptorsRewriteRequestToServer() throws Exception {
    rewriteRequestToServer(client.networkInterceptors());
  }

  private void rewriteRequestToServer(List<Interceptor> interceptors) throws Exception {
    server.enqueue(new MockResponse());

    interceptors.add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Request originalRequest = chain.request();
        return chain.proceed(originalRequest.newBuilder()
            .method("POST", uppercase(originalRequest.body()))
            .addHeader("OkHttp-Intercepted", "yep")
            .build());
      }
    });

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .addHeader("Original-Header", "foo")
        .method("PUT", RequestBody.create(MediaType.parse("text/plain"), "abc"))
        .build();

    executeSynchronously(request);

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals("ABC", recordedRequest.getUtf8Body());
    assertEquals("foo", recordedRequest.getHeader("Original-Header"));
    assertEquals("yep", recordedRequest.getHeader("OkHttp-Intercepted"));
    assertEquals("POST", recordedRequest.getMethod());
  }

  @Test public void applicationInterceptorsRewriteResponseFromServer() throws Exception {
    rewriteResponseFromServer(client.interceptors());
  }

  @Test public void networkInterceptorsRewriteResponseFromServer() throws Exception {
    rewriteResponseFromServer(client.networkInterceptors());
  }

  private void rewriteResponseFromServer(List<Interceptor> interceptors) throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Original-Header: foo")
        .setBody("abc"));

    interceptors.add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Response originalResponse = chain.proceed(chain.request());
        return originalResponse.newBuilder()
            .body(uppercase(originalResponse.body()))
            .addHeader("OkHttp-Intercepted", "yep")
            .build();
      }
    });

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();

    Response response = executeSynchronously(request);
    assertEquals("ABC", response.body().string());
    assertEquals("yep", response.header("OkHttp-Intercepted"));
    assertEquals("foo", response.header("Original-Header"));
  }

  @Test public void multipleApplicationInterceptors() throws Exception {
    multipleInterceptors(client.interceptors());
  }

  @Test public void multipleNetworkInterceptors() throws Exception {
    multipleInterceptors(client.networkInterceptors());
  }

  private void multipleInterceptors(List<Interceptor> interceptors) throws Exception {
    server.enqueue(new MockResponse());

    interceptors.add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Request originalRequest = chain.request();
        Response originalResponse = chain.proceed(originalRequest.newBuilder()
            .addHeader("Request-Interceptor", "Android") // 1. Added first.
            .build());
        return originalResponse.newBuilder()
            .addHeader("Response-Interceptor", "Donut") // 4. Added last.
            .build();
      }
    });
    interceptors.add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Request originalRequest = chain.request();
        Response originalResponse = chain.proceed(originalRequest.newBuilder()
            .addHeader("Request-Interceptor", "Bob") // 2. Added second.
            .build());
        return originalResponse.newBuilder()
            .addHeader("Response-Interceptor", "Cupcake") // 3. Added third.
            .build();
      }
    });

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();

    Response response = executeSynchronously(request);
    assertEquals(Arrays.asList("Cupcake", "Donut"),
        response.headers("Response-Interceptor"));

    RecordedRequest recordedRequest = server.takeRequest();
    assertEquals(Arrays.asList("Android", "Bob"),
        recordedRequest.getHeaders("Request-Interceptor"));
  }

  @Test public void asyncApplicationInterceptors() throws Exception {
    asyncInterceptors(client.interceptors());
  }

  @Test public void asyncNetworkInterceptors() throws Exception {
    asyncInterceptors(client.networkInterceptors());
  }

  private void asyncInterceptors(List<Interceptor> interceptors) throws Exception {
    server.enqueue(new MockResponse());

    interceptors.add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        Response originalResponse = chain.proceed(chain.request());
        return originalResponse.newBuilder()
            .addHeader("OkHttp-Intercepted", "yep")
            .build();
      }
    });

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();
    client.newCall(request).enqueue(callback);

    callback.await(request.url())
        .assertCode(200)
        .assertHeader("OkHttp-Intercepted", "yep");
  }

  @Test public void applicationInterceptorsCanMakeMultipleRequestsToServer() throws Exception {
    server.enqueue(new MockResponse().setBody("a"));
    server.enqueue(new MockResponse().setBody("b"));

    client.interceptors().add(new Interceptor() {
      @Override public Response intercept(Chain chain) throws IOException {
        chain.proceed(chain.request());
        return chain.proceed(chain.request());
      }
    });

    Request request = new Request.Builder()
        .url(server.getUrl("/"))
        .build();

    Response response = executeSynchronously(request);
    assertEquals(response.body().string(), "b");
  }

  private RequestBody uppercase(final RequestBody original) {
    return new RequestBody() {
      @Override public MediaType contentType() {
        return original.contentType();
      }

      @Override public long contentLength() throws IOException {
        return original.contentLength();
      }

      @Override public void writeTo(BufferedSink sink) throws IOException {
        Sink uppercase = uppercase(sink);
        BufferedSink bufferedSink = Okio.buffer(uppercase);
        original.writeTo(bufferedSink);
        bufferedSink.emit();
      }
    };
  }

  private Sink uppercase(final BufferedSink original) {
    return new ForwardingSink(original) {
      @Override public void write(Buffer source, long byteCount) throws IOException {
        original.writeUtf8(source.readUtf8(byteCount).toUpperCase(Locale.US));
      }
    };
  }

  static ResponseBody uppercase(ResponseBody original) {
    return ResponseBody.create(original.contentType(), original.contentLength(),
        Okio.buffer(uppercase(original.source())));
  }

  private static Source uppercase(final Source original) {
    return new ForwardingSource(original) {
      @Override public long read(Buffer sink, long byteCount) throws IOException {
        Buffer mixedCase = new Buffer();
        long count = original.read(mixedCase, byteCount);
        sink.writeUtf8(mixedCase.readUtf8().toUpperCase(Locale.US));
        return count;
      }
    };
  }

  private Buffer gzip(String data) throws IOException {
    Buffer result = new Buffer();
    BufferedSink sink = Okio.buffer(new GzipSink(result));
    sink.writeUtf8(data);
    sink.close();
    return result;
  }

  private Response executeSynchronously(final Request request) throws InterruptedException, IOException, ExecutionException {
    return executeSynchronously(client.newCall(request));
  }

  private Response executeSynchronously(final Call call) throws InterruptedException, IOException, ExecutionException {
    Response response = null;
    try {
        response = new Fiber<Response>() {
            @Override
            protected Response run() throws SuspendExecution, InterruptedException {
                try {
                    return call.execute();
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }.start().get();
    } catch (ExecutionException ee) {
        if (ee.getCause() instanceof RuntimeException) {
            final RuntimeException re = (RuntimeException) ee.getCause();
            if (re.getCause() instanceof IOException)
                throw (IOException) re.getCause();
            else
                throw re;
        } else if (ee.getCause() instanceof IllegalStateException)
            throw ((IllegalStateException) ee.getCause());
        else
            throw ee;
    }
    return response;
  }
}
