package com.pehrs.spring.ai.vespa;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.JdkFutureAdapters;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
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
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;

/**
 * This implementation uses guice and apache httpclient5
 * for more detailed control over the parallelism.
 */
public class VespaAsyncHttp5VectorStore implements VectorStore, Closeable {

  private static Logger log = LoggerFactory.getLogger(VespaVectorStore.class);
  private static ObjectMapper objectMapper = new ObjectMapper();

  private final VespaConfig config;

  private final EmbeddingClient embeddingClient;
  private final String queryUrl;

  private final String vespaFullNs;
  private final CloseableHttpAsyncClient client;
  private final ListeningExecutorService executorService;
  private final Counter insertCounter;
  private final Counter embeddingCounter;
  private String queryTemplate;

  private final Histogram embeddingHistogram;
  private final Histogram insertHistogram;
  private final Histogram queryHistogram;
  private final Meter insertMeter;
  private final Meter queryMeter;

  public VespaAsyncHttp5VectorStore(
      MetricRegistry metricRegistry, EmbeddingClient embeddingClient,
      VespaConfig config
  ) throws IOException {
    this.embeddingClient = embeddingClient;
    this.config = config;
    this.queryUrl = String.format("%s%s", this.config.containerEndpoint, this.config.queryUri);

    // Metrics
    this.insertMeter = metricRegistry.meter("insert.rps");
    this.queryMeter = metricRegistry.meter("query.rps");

    this.insertCounter = metricRegistry.counter("insert.pending");
    this.embeddingCounter = metricRegistry.counter("embedding.pending");

    this.embeddingHistogram = metricRegistry.histogram("embedding.ms");
    this.insertHistogram = metricRegistry.histogram("insert.ms");
    this.queryHistogram = metricRegistry.histogram("query.ms");

    this.queryTemplate = new ClassPathResource("/vespa-query.template").getContentAsString(
        Charset.defaultCharset());

    this.vespaFullNs = "id:" + this.config.namespace + ":" + this.config.docType + "::";

    ExecutorService jdkExecService = Executors.newFixedThreadPool(config.threadPoolSize);
    this.executorService = MoreExecutors.listeningDecorator(jdkExecService);

    int maxSize = 16 * 1024 * 1024;

    final IOReactorConfig ioReactorConfig = IOReactorConfig.custom()
        .setSoTimeout(Timeout.ofSeconds(60))
        .setSndBufSize(maxSize)
        .setRcvBufSize(maxSize)
        .build();

    this.client = HttpAsyncClients.custom()
        .setIOReactorConfig(ioReactorConfig)
        .build();
    this.client.start();

  }


  public record VespaEmbedding(List<Double> values) {

  }

  public record VespaDoc(Map<String, Object> fields) {

  }

  public record VespaInsertReq(String docId, String vespaJson) {

  }


  private String vespaDocApiUrl(String id) {
    return
        String.format("%s/document/v1/%s/%s/docid/%s",
            this.config.containerEndpoint,
            this.config.namespace, this.config.docType, id);
  }


  @Override
  public void close() {
    System.out.println(VespaAsyncHttp5VectorStore.class.getSimpleName() + ": close()");

    this.executorService.shutdown();
    try {
      // Wait a while for existing tasks to terminate
      if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
        executorService.shutdownNow(); // Cancel currently executing tasks
        // Wait a while for tasks to respond to being cancelled
        if (!executorService.awaitTermination(60, TimeUnit.SECONDS))
          System.err.println("Pool did not terminate");
      }
    } catch (InterruptedException ie) {
      // (Re-)Cancel if current thread also interrupted
      executorService.shutdownNow();
      // Preserve interrupt status
      Thread.currentThread().interrupt();
    }

    client.close(CloseMode.GRACEFUL);
  }

  @Override
  public void add(List<Document> documents) {

    AsyncFunction<Document, VespaInsertReq> toVespaInsertReq = document -> this.executorService.submit(
        () -> {
          embeddingCounter.inc();
          long start = System.currentTimeMillis();
          List<Double> embedding = this.embeddingClient.embed(document);
          this.embeddingHistogram.update(System.currentTimeMillis() - start);
          embeddingCounter.dec();

          // Create vespa /document/v1 doc request
          Map<String, Object> fields = new HashMap<>();
          fields.put("content", document.getContent());
          fields.put("embedding", new VespaVectorStore.VespaEmbedding(embedding));
          VespaVectorStore.VespaDoc vespaDoc = new VespaVectorStore.VespaDoc(fields);
          try {
            return new VespaInsertReq(
                document.getId(),
                objectMapper.writeValueAsString(vespaDoc)
            );
          } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
          }
        });

    AsyncFunction<VespaInsertReq, SimpleHttpResponse> insertIntoVespa = insertReq -> {
      String docApiUrl = vespaDocApiUrl(insertReq.docId);
      log.debug("Vespa yql request (" + docApiUrl + "): " + insertReq.vespaJson);
      long start = System.currentTimeMillis();
      this.insertCounter.inc();
      // Call the document API
      final SimpleHttpRequest request = SimpleRequestBuilder.post(docApiUrl)
          .setBody(insertReq.vespaJson, ContentType.APPLICATION_JSON)
          .build();
      return JdkFutureAdapters.listenInPoolThread(client.execute(
          SimpleRequestProducer.create(request),
          SimpleResponseConsumer.create(),
          new FutureCallback<>() {
            @Override
            public void completed(final SimpleHttpResponse response) {
              insertCounter.dec();
              // We only measure successful calls :-)
              insertHistogram.update(System.currentTimeMillis() - start);
              insertMeter.mark();
              log.debug(request + "->" + new StatusLine(response));
              log.trace("" + response.getBody());
            }

            @Override
            public void failed(final Exception ex) {
              insertCounter.dec();
              System.out.println(request + "->" + ex);
            }

            @Override
            public void cancelled() {
              insertCounter.dec();
              System.out.println(request + " cancelled");
            }
          }));
    };

    List<ListenableFuture<SimpleHttpResponse>> calls = documents.stream()
        .map(doc -> {
          ListenableFuture<Document> getDocTask = Futures.immediateFuture(doc);
          ListenableFuture<VespaInsertReq> toReq = Futures.transformAsync(getDocTask,
              toVespaInsertReq,
              this.executorService);
          return Futures.transformAsync(toReq, insertIntoVespa,
              this.executorService);
        })
        .collect(Collectors.toList());

    try {
      Futures.allAsList(calls).get(10, TimeUnit.MINUTES);
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Optional<Boolean> delete(List<String> idList) {
    return Optional.empty();
  }

  @Override
  public List<Document> similaritySearch(SearchRequest request) {

    try {
      if (request.getFilterExpression() != null) {
        throw new UnsupportedOperationException(
            "The [" + this.getClass() + "] doesn't support metadata filtering!");
      }

      long start = System.currentTimeMillis();
      List<Double> queryEmbedding = embeddingClient.embed(request.getQuery());
      this.embeddingHistogram.update(System.currentTimeMillis() - start);
      String yqlRequest = createYqlRequest(queryEmbedding);
      log.debug("yql: " + yqlRequest);

      start = System.currentTimeMillis();
      final SimpleHttpRequest httpRequest = SimpleRequestBuilder.get(queryUrl)
          .setBody(yqlRequest, ContentType.APPLICATION_JSON)
          .build();

      final Future<SimpleHttpResponse> future = client.execute(
          SimpleRequestProducer.create(httpRequest),
          SimpleResponseConsumer.create(),
          new FutureCallback<SimpleHttpResponse>() {

            @Override
            public void completed(final SimpleHttpResponse response) {
              log.debug(request + "->" + new StatusLine(response));
              log.trace("" + response.getBody());
            }

            @Override
            public void failed(final Exception ex) {
              System.out.println(request + "->" + ex);
            }

            @Override
            public void cancelled() {
              System.out.println(request + " cancelled");
            }

          });
      SimpleHttpResponse response = future.get(10, TimeUnit.SECONDS);
      this.queryHistogram.update(System.currentTimeMillis() - start);
      this.queryMeter.mark();

      String responseBody = response.getBodyText();
      JsonNode responseJson = objectMapper.readTree(responseBody);
      JsonNode root = responseJson.get("root");
      if (root.has("errors")) {
        JsonNode errors = root.get("errors");
        throw new RuntimeException(objectMapper.writeValueAsString(errors));
      }
      JsonNode children = responseJson.get("root").get("children");

      List<Document> aiDocs = new ArrayList<>();
      children.forEach(childNode -> {
        String docId = childNode.get("id").asText().replace(vespaFullNs, "");
        JsonNode jsonFields = childNode.get("fields");
        String content = jsonFields.get(this.config.contentFieldName).asText();
        ArrayNode jsonEmbeddingValues =
            (ArrayNode) jsonFields.get(this.config.embeddingFieldName).get("values");
        List<Double> embedding = new ArrayList<>();
        jsonEmbeddingValues.forEach(valueNode -> {
          Double embeddingValue = valueNode.asDouble();
          embedding.add(embeddingValue);
        });
        Document aiDoc = new Document(docId, content, new HashMap<>());
        aiDoc.setEmbedding(embedding);
        aiDocs.add(aiDoc);
      });

      return aiDocs;
    } catch (IOException e) {
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    } catch (TimeoutException e) {
      throw new RuntimeException(e);
    }
  }


  private String createYqlRequest(List<Double> queryEmbedding) {
    String queryEmbeddingStr = queryEmbedding.stream().map(d -> "" + d)
        .collect(Collectors.joining(","));

    String targetHits = String.format("{targetHits:%d}", this.config.targetHits);
    String fields = String.format("%s, %s", this.config.embeddingFieldName,
        this.config.contentFieldName);
    String yqlRequest = this.queryTemplate.replace("{targetHits}", targetHits);
    yqlRequest = yqlRequest.replace("{rankingName}", this.config.rankingName);
    yqlRequest = yqlRequest.replace("{rankingInputName}", this.config.rankingInputName);
    yqlRequest = yqlRequest.replace("{embeddingFieldName}", this.config.embeddingFieldName);
    yqlRequest = yqlRequest.replace("{docType}", this.config.docType);
    yqlRequest = yqlRequest.replace("{fields}", fields);
    yqlRequest = yqlRequest.replace("{embedding}", queryEmbeddingStr);
    return yqlRequest;
  }

}
