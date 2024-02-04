package com.pehrs.spring.ai.etl;

import com.codahale.metrics.Clock;
import com.codahale.metrics.MetricRegistry;
import com.pehrs.spring.ai.rss.RssXmlAiDocumentReader;
import com.pehrs.spring.ai.util.ConsoleTableReporter;
import com.pehrs.spring.ai.vespa.VespaConfig;
import com.pehrs.spring.ai.vespa.VespaVectorStore;
import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.ai.ollama.OllamaEmbeddingClient;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.batch.item.ItemReader;

public class SingleThreadBatchJob {

  public static void main(String[] args) throws IllegalAccessException {

    long start = System.currentTimeMillis();

    SingleThreadBatchJob batch = new SingleThreadBatchJob();
    batch.run();

    long ms = System.currentTimeMillis() - start;
    Duration duration = Duration.of(ms, ChronoUnit.MILLIS);
    System.out.println("Execution took " + duration);
  }


  public ItemReader<Document> reader(MetricRegistry metricRegistry) throws ParserConfigurationException {

    List<String> rssFeeds = List.of(
        "http://www.svt.se/nyheter/rss.xml",
        "http://www.dn.se/nyheter/m/rss/senaste-nytt",
        "https://rss.aftonbladet.se/rss2/small/pages/sections/senastenytt/"
    );
    String rssFeedsProp = System.getProperty("rss.feeds");
    if (rssFeedsProp != null) {
      rssFeeds = Arrays.stream(rssFeedsProp.split(",")).toList();
    }
    System.out.println("\n=========================\nLoading news from " + rssFeeds
        + "\n=========================\n");
    return new RssXmlAiDocumentReader(metricRegistry, rssFeeds);
  }


  public VectorStore vectorStore(MetricRegistry metricRegistry)
      throws IOException {

    VespaConfig vespaConfig = VespaConfig.fromClassPath("vespa.yaml");


    ConsoleTableReporter reporter = ConsoleTableReporter.forRegistry(metricRegistry)
        .withClock(Clock.defaultClock())
        .outputTo(System.out)
        .build();
    reporter.start(2, 2, TimeUnit.SECONDS);

    OllamaApi ollamaClient = new OllamaApi();
    EmbeddingClient embeddingClient = new OllamaEmbeddingClient(ollamaClient)
        .withModel("mistral");

    return new VespaVectorStore(metricRegistry, embeddingClient, vespaConfig);
  }

  void run() {
    try {
      MetricRegistry metricRegistry = new MetricRegistry();
      VectorStore vectorStore = vectorStore(metricRegistry);
      ItemReader<Document> reader = reader(metricRegistry);

      TextSplitter textSplitter = new TokenTextSplitter();
      // Create a stream of documents
      Stream<Document> docStream = Stream.generate(() -> {
        try {
          return reader.read();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      })
          .takeWhile(doc -> doc != null)
          .flatMap(doc -> textSplitter.apply(List.of(doc)).stream());

//          // Consume docs via Reactor Flux
//          int batchSize = 10;
//          Flux.fromStream(docStream)
//              .buffer(batchSize)
//              .subscribe(docs -> {
//                vectorStore.add(docs);
//              });

      // Do one by one for now...
      docStream.forEach(document -> {
        if(document != null) {
          vectorStore.add(List.of(document));
        } else {
          System.out.println("----------------> null document!");
        }
      });

    } catch (ParserConfigurationException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

}
