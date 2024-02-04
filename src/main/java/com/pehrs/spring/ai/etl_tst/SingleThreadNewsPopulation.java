package com.pehrs.spring.ai.etl_tst;

import com.codahale.metrics.Clock;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.JdkFutureAdapters;
import com.google.common.util.concurrent.ListenableFuture;
import com.pehrs.spring.ai.util.ConsoleTableReporter;
import com.pehrs.spring.ai.vespa.VespaConfig;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.async.methods.SimpleRequestProducer;
import org.apache.hc.client5.http.async.methods.SimpleResponseConsumer;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

public class SingleThreadNewsPopulation {

  static ObjectMapper mapper = new ObjectMapper();

  static final Logger log = LoggerFactory.getLogger(SingleThreadNewsPopulation.class);

  public static Stream<String> lines(ReadableByteChannel channel) {
    BufferedReader br = new BufferedReader(Channels.newReader(channel, "UTF-8"));
    return br.lines().onClose(() -> {
      try {
        br.close();
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      }
    });
  }

  private static CloseableHttpAsyncClient createClient() {
    int maxSize = 16 * 1024 * 1024;

    final IOReactorConfig ioReactorConfig = IOReactorConfig.custom()
        .setSoTimeout(Timeout.ofSeconds(60))
        .setSndBufSize(maxSize)
        .setRcvBufSize(maxSize)
        .build();

    CloseableHttpAsyncClient client = HttpAsyncClients.custom()
        .setIOReactorConfig(ioReactorConfig)
        .build();
    client.start();
    return client;
  }

  public static void main(String[] args)
      throws IOException, ExecutionException, InterruptedException, TimeoutException {

    long applicationStartNs = System.nanoTime();

    MetricRegistry metricRegistry = new MetricRegistry();
    ConsoleTableReporter reporter = ConsoleTableReporter.forRegistry(metricRegistry)
        .withClock(Clock.defaultClock())
        .outputTo(System.out)
        .histogramScaleFactor("vespa.insert.ms", 1_000_000d)
        .build();

    final Histogram vespaInsertHistogram = metricRegistry.histogram(
        "vespa.insert.ms");
    final Counter missingNewsCounter = metricRegistry.counter("missing.news.count");
    final Counter insertSuccessCounter = metricRegistry.counter("vespa.insert.success");
    final Counter insertFailCounter = metricRegistry.counter("vespa.insert.failures");
    final Counter insertCancelCounter = metricRegistry.counter("vespa.insert.cancel");

    reporter.start(2, 2, TimeUnit.SECONDS);

    Resource newsTsvResource = new FileSystemResource("vespa-data/news.tsv");
    final Map<String, NewsRecord> inputNewsRecords = lines(newsTsvResource.readableChannel())
        .map(line -> line.split("\t"))
        .map(tsv -> new NewsRecord(
            tsv[0],
            tsv[3],
            tsv[4],
            tsv[5],
            List.of()
        ))
        // .limit(100) // for development
        .collect(Collectors.toMap(
            newsRecord -> newsRecord.newsId(),
            newsRecord -> newsRecord
        ));

    Resource newsEmbeddingsTsvResource = new FileSystemResource("vespa-data/news_embeddings.tsv");
    List<NewsRecord> embedRecords = lines(newsEmbeddingsTsvResource.readableChannel())
        .map(line -> line.split("\t"))
        .map(tsv -> {
          String newsId = tsv[0];
          String embeddingStr = tsv[1];
          NewsRecord newsRecord = inputNewsRecords.get(newsId);
          if (newsRecord != null) {
            List<Double> embedding = Arrays.stream(embeddingStr.split(","))
                .map(str -> Double.parseDouble(str))
                .collect(Collectors.toList());
            return inputNewsRecords.get(newsId).withEmbedding(embedding);
          } else {
            missingNewsCounter.inc();
            return null;
          }
        }).collect(Collectors.toList());
    Map<String, NewsRecord> newsRecords =
        embedRecords.stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toMap(
                newsRecord -> newsRecord.newsId(),
                newsRecord -> newsRecord
            ));

    // newsRecords.values().stream().forEach(newsRecord -> System.out.println(newsRecord));

    CloseableHttpAsyncClient client = createClient();
    final VespaConfig vespaConfig = VespaConfig.fromClassPath("vespa.yaml");

    String containerEndpoint = "http://localhost:8080";
    String namespace = "news";
    String docType = "news";

    boolean singleThreaded = false;
    if(singleThreaded) {
      Map<Integer, Long> resultCodes = newsRecords.values().stream().mapToInt(newsRecord -> {
            try {
              long start = System.nanoTime();
              String vespaJson = newsRecord.toVespaInsertJson();
              String url = String.format("%s/document/v1/%s/%s/docid/%s",
                  containerEndpoint,
                  namespace,
                  docType,
                  newsRecord.newsId());
              final SimpleHttpRequest request = SimpleRequestBuilder.post(url)
                  .setBody(vespaJson, ContentType.APPLICATION_JSON)
                  .build();
              // System.out.println("insert " + newsRecord.newsId());
              Future<SimpleHttpResponse> resFuture = client.execute(
                  SimpleRequestProducer.create(request),
                  SimpleResponseConsumer.create(),
                  new FutureCallback<>() {
                    @Override
                    public void completed(final SimpleHttpResponse response) {
                      long end = System.nanoTime();
                      vespaInsertHistogram.update(end - start);
                      insertSuccessCounter.inc();
                    }

                    @Override
                    public void failed(final Exception ex) {
                      // System.out.println(request + "->" + ex);
                      insertFailCounter.inc();
                    }

                    @Override
                    public void cancelled() {
                      // System.out.println(request + " cancelled");
                      insertCancelCounter.inc();
                    }
                  });

              SimpleHttpResponse response = resFuture.get(20, TimeUnit.SECONDS);
              log.debug(request + "->" + new StatusLine(response));
              log.trace("" + response.getBody().getBodyText());

              return response.getCode();

            } catch (JsonProcessingException e) {
              throw new RuntimeException(e);
            } catch (ExecutionException e) {
              throw new RuntimeException(e);
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            } catch (TimeoutException e) {
              throw new RuntimeException(e);
            }
          })
          .boxed()
          .collect(Collectors.groupingBy(
              Function.identity(),
              Collectors.counting()));

      System.out.println("Insert Result Codes: " + resultCodes);
    } else {
      List<ListenableFuture<SimpleHttpResponse>> responses = newsRecords.values().stream()
          .map(newsRecord -> {
            try {
              long start = System.nanoTime();
              String vespaJson = newsRecord.toVespaInsertJson();
              String url = String.format("%s/document/v1/%s/%s/docid/%s",
                  containerEndpoint,
                  namespace,
                  docType,
                  newsRecord.newsId());
              final SimpleHttpRequest request = SimpleRequestBuilder.post(url)
                  .setBody(vespaJson, ContentType.APPLICATION_JSON)
                  .build();
              // System.out.println("insert " + newsRecord.newsId());
              return JdkFutureAdapters.listenInPoolThread(client.execute(
                  SimpleRequestProducer.create(request),
                  SimpleResponseConsumer.create(),
                  new FutureCallback<SimpleHttpResponse>() {
                    @Override
                    public void completed(final SimpleHttpResponse response) {
                      long end = System.nanoTime();
                      vespaInsertHistogram.update(end - start);
                      insertSuccessCounter.inc();
                    }

                    @Override
                    public void failed(final Exception ex) {
                      // System.out.println(request + "->" + ex);
                      insertFailCounter.inc();
                    }

                    @Override
                    public void cancelled() {
                      // System.out.println(request + " cancelled");
                      insertCancelCounter.inc();
                    }
                  }));

            } catch (JsonProcessingException e) {
              throw new RuntimeException(e);
            }
          })
          .collect(Collectors.toList());

      Futures.allAsList(responses).get(10, TimeUnit.MINUTES);
    }

    client.close(CloseMode.GRACEFUL);

    long applicationDoneNs = System.nanoTime();

    Duration applicationDuration =
        Duration.of(applicationDoneNs - applicationStartNs, ChronoUnit.NANOS);

    reporter.report();

    System.err.println("Executed in " + applicationDuration);
    System.err.println("Input news records " + newsRecords.size());
    System.err.println("Input embed records " + embedRecords.size());
  }

}
