package com.pehrs.spring.ai.service;

import com.codahale.metrics.Clock;
import com.codahale.metrics.MetricRegistry;
import com.pehrs.spring.ai.util.ConsoleTableReporter;
import com.pehrs.spring.ai.vespa.VespaConfig;
import com.pehrs.spring.ai.vespa.VespaVectorStore;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan({"com.pehrs.spring.ai.service", "com.pehrs.spring.ai.vespa"})
public class RagSampleService {

  public static void main(String[] args) {
    SpringApplication.run(RagSampleService.class, args);
  }

  @Value("${app.vectorstore.path:/tmp/vectorstore.json}")
  private String vectorStorePath;

  @Bean
  VespaVectorStore vespaVectorStore(MetricRegistry metricRegistry, EmbeddingClient embeddingClient, VespaConfig config)
      throws IOException {
    VespaVectorStore vespaVectorStore = new VespaVectorStore(metricRegistry, embeddingClient, config);
    return vespaVectorStore;
  }

  @Bean(destroyMethod = "close")
  public ConsoleTableReporter metricsReporter(MetricRegistry registry) {
    ConsoleTableReporter reporter = ConsoleTableReporter.forRegistry(registry)
        .withClock(Clock.defaultClock())
        .outputTo(System.out)
        .showZeroMetrics(false)
        .build();
    reporter.start(4, 10, TimeUnit.SECONDS);
    return reporter;
  }

  @Bean
  public MetricRegistry metricRegistry() {
    return new MetricRegistry();
  }


}
