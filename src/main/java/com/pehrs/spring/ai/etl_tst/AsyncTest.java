package com.pehrs.spring.ai.etl_tst;

import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.JdkFutureAdapters;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import java.util.stream.Collectors;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.async.methods.SimpleRequestProducer;
import org.apache.hc.client5.http.async.methods.SimpleResponseConsumer;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.Timeout;

public class AsyncTest {

  public static void main(String[] args) throws ExecutionException, InterruptedException {
    final IOReactorConfig ioReactorConfig = IOReactorConfig.custom()
        .setSoTimeout(Timeout.ofSeconds(5))
        .build();

    final CloseableHttpAsyncClient client = HttpAsyncClients.custom()
        .setIOReactorConfig(ioReactorConfig)
        .build();

    client.start();

    final HttpHost target = new HttpHost("httpbin.org");
    final String[] requestUris = new String[]{"/", "/ip", "/user-agent", "/headers"};

    List<ListenableFuture<SimpleHttpResponse>> responses = Arrays.stream(requestUris)
        .map(requestUri -> {

          final SimpleHttpRequest request = SimpleRequestBuilder.get()
              .setHttpHost(target)
              .setPath(requestUri)
              .build();

          System.out.println("Executing request " + request);
          return JdkFutureAdapters.listenInPoolThread(client.execute(
              SimpleRequestProducer.create(request),
              SimpleResponseConsumer.create(),
              new FutureCallback<SimpleHttpResponse>() {

                @Override
                public void completed(final SimpleHttpResponse response) {
                  System.out.println(request + "->" + new StatusLine(response));
                  System.out.println(response.getBody());
                }

                @Override
                public void failed(final Exception ex) {
                  System.out.println(request + "->" + ex);
                }

                @Override
                public void cancelled() {
                  System.out.println(request + " cancelled");
                }

              }));
        }).collect(Collectors.toList());


    Futures.allAsList(responses);

    System.out.println("Shutting down");
    client.close(CloseMode.GRACEFUL);
  }

}
