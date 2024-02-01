package com.pehrs.spring.ai.vespa;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

public class VespaVectorStore implements VectorStore {

  private static Logger log = LoggerFactory.getLogger(VespaVectorStore.class);

  private final EmbeddingClient embeddingClient;
  private final WebClient webClient;

  private static ObjectMapper objectMapper = new ObjectMapper();

  private final VespaConfig config;
  private final String queryUrl;
  private final Histogram embeddingHistogram;
  private final Histogram insertHistogram;
  private final Histogram queryHistogram;
  private final Meter insertMeter;
  private final Meter queryMeter;
  private final String vespaFullNs;
  private String queryTemplate;

  // Not strictly needed, I just wanted to see some progress while inserting document

  public VespaVectorStore(
      MetricRegistry metricRegistry, EmbeddingClient embeddingClient,
      VespaConfig config
  ) throws IOException {
    this.embeddingClient = embeddingClient;
    this.config = config;
    this.queryUrl = String.format("%s%s", this.config.containerEndpoint, this.config.queryUri);

    // Metrics
    this.insertMeter = metricRegistry.meter("insert.rps");
    this.queryMeter = metricRegistry.meter("query.rps");

    this.embeddingHistogram = metricRegistry.histogram("embedding.ms");
    this.insertHistogram = metricRegistry.histogram("insert.ms");
    this.queryHistogram = metricRegistry.histogram("query.ms");

    this.queryTemplate = new ClassPathResource("/vespa-query.template").getContentAsString(
        Charset.defaultCharset());

    int maxSize = 16 * 1024 * 1024;
    final ExchangeStrategies strategies = ExchangeStrategies.builder()
        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxSize))
        .build();
    this.webClient = WebClient.builder()
        .clientConnector(new ReactorClientHttpConnector(
            HttpClient.create().followRedirect(true)
        ))
        .exchangeStrategies(strategies)
        .build();

    this.vespaFullNs = "id:" + this.config.namespace + ":" + this.config.docType + "::";

  }

  public record VespaEmbedding(List<Double> values) {

  }

  public record VespaDoc(Map<String, Object> fields) {

  }

  private void addAiDoc(Document aiDoc) {
    try {
      // Create embedding

      long start = System.currentTimeMillis();
      List<Double> embedding = this.embeddingClient.embed(aiDoc);
      this.embeddingHistogram.update(System.currentTimeMillis() - start);

      // Create vespa /document/v1 doc request
      Map<String, Object> fields = new HashMap<>();
      fields.put("content", aiDoc.getContent());
      fields.put("embedding", new VespaEmbedding(embedding));
      VespaDoc vespaDoc = new VespaDoc(fields);
      String vespaJson = objectMapper.writeValueAsString(vespaDoc);

      String docApiUrl = vespaDocApiUrl(aiDoc.getId());
      log.debug("Vespa yql request (" + docApiUrl + "): " + vespaJson);

      // Call the document API
      start = System.currentTimeMillis();
      String response = this.webClient.post()
          .uri(docApiUrl)
          .bodyValue(vespaJson)
          .header("Content-Type", "application/json")
          .retrieve().bodyToMono(String.class).block();
      this.insertHistogram.update(System.currentTimeMillis() - start);
      this.insertMeter.mark();

      log.debug("response: " + response);

    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  private String vespaDocApiUrl(String id) {
    return
        String.format("%s/document/v1/%s/%s/docid/%s",
            this.config.containerEndpoint,
            this.config.namespace, this.config.docType, id);
  }

  @Override
  public void add(List<Document> documents) {
    // FIXME: We should figure out how to call the
    //  "batch" vespa document-api instead of putting individual documents
    documents.forEach(this::addAiDoc);
  }

  private void deleteDoc(String id) {
    throw new RuntimeException("Not implemented yet!");
  }

  @Override
  public Optional<Boolean> delete(List<String> idList) {
    idList.forEach(this::deleteDoc);
    return Optional.of(true);
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
      String responseBody = webClient.post()
          .uri(queryUrl)
          .bodyValue(yqlRequest)
          .accept(MediaType.APPLICATION_JSON)
          .header("Content-Type", "application/json")
          .retrieve().bodyToMono(String.class).block();
      this.queryHistogram.update(System.currentTimeMillis() - start);

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
