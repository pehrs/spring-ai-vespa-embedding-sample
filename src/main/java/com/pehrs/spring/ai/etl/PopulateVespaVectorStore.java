package com.pehrs.spring.ai.etl;

import com.codahale.metrics.Clock;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Reporter;
import com.pehrs.spring.ai.util.ConsoleTableReporter;
import com.pehrs.spring.ai.vespa.VespaAsyncHttp5VectorStore;
import com.pehrs.spring.ai.vespa.VespaConfig;
import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import reactor.core.publisher.Hooks;

@SpringBootApplication()
@ComponentScan({"com.pehrs.spring.ai.etl", "com.pehrs.spring.ai.vespa"})
public class PopulateVespaVectorStore {

  public static void main(String[] args) throws Exception {

    // FIXME: Is this needed?
    Hooks.enableAutomaticContextPropagation();

    long start = System.currentTimeMillis();
    SpringApplication app = new SpringApplication(PopulateVespaVectorStore.class);
    app.setWebApplicationType(WebApplicationType.NONE);
    ApplicationContext ctx = app.run(args);
    SpringApplication.exit(ctx);
    long ms = System.currentTimeMillis() - start;
    Duration duration = Duration.of(ms, ChronoUnit.MILLIS);
    System.out.println("Execution took " + duration);
  }

  @Bean
  public VectorStore vectorStore(MetricRegistry metricRegistry, Reporter reporter,
      EmbeddingClient embeddingClient, VespaConfig vespaConfig)
      throws IOException {
    return new VespaAsyncHttp5VectorStore(metricRegistry, embeddingClient, vespaConfig);
    // return new VespaVectorStore(metricRegistry, embeddingClient, vespaConfig);
  }

  @Bean(destroyMethod = "close")
  public ConsoleTableReporter metricsReporter(MetricRegistry registry) {
    ConsoleTableReporter reporter = ConsoleTableReporter.forRegistry(registry)
        .withClock(Clock.defaultClock())
        .outputTo(System.out)
        .build();
    reporter.start(2, 2, TimeUnit.SECONDS);
    return reporter;
  }

  @Bean
  public MetricRegistry metricRegistry() {
    return new MetricRegistry();
  }

}
