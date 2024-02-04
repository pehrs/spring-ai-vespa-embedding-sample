package com.pehrs.spring.ai.etl;

import com.codahale.metrics.MetricRegistry;
import com.pehrs.spring.ai.rss.RssXmlAiDocumentReader;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.xml.parsers.ParserConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobInterruptedException;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import reactor.core.publisher.Flux;

@Configuration
public class BatchConfig {

  @Bean
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

  @Bean
  public Job job1(
      JobRepository jobRepository, JobCompletionNotificationListener listener, Step step1) {
    return new JobBuilder("job1", jobRepository)
        .incrementer(new RunIdIncrementer())
        .listener(listener)
        .flow(step1)
        .end()
        .build();
  }

  @Bean
  public TaskExecutor taskExecutor() {
    return new SimpleAsyncTaskExecutor("spring_batch");
  }

  @Bean
  public Step importDocsToVespa(TaskExecutor taskExecutor, JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      ItemReader<Document> reader,
      ItemWriter<Document> writer) {
    return new StepBuilder("importDocsToVespa", jobRepository)
        .<Document, Document> chunk(18, transactionManager)
        .reader(reader)
        // .processor(processor())
        .taskExecutor(taskExecutor)
        .writer(writer)
        .build();
  }


//  @Bean
//  public Step importDocsToVespa(VectorStore vectorStore) {
//    return new Step() {
//      @Override
//      public String getName() {
//        return "insertEmbeddings";
//      }
//
//      @Override
//      public void execute(StepExecution stepExecution) throws JobInterruptedException {
//        try {
//          ItemReader<Document> reader = reader();
//
//          // Create a stream of documents
//          Stream<Document> docStream = Stream.generate(() -> {
//            try {
//              return reader.read();
//            } catch (Exception e) {
//              throw new RuntimeException(e);
//            }
//          });
//
////          // Consume docs via Reactor Flux
////          int batchSize = 10;
////          Flux.fromStream(docStream)
////              .buffer(batchSize)
////              .subscribe(docs -> {
////                vectorStore.add(docs);
////              });
//
//          // Do one by one for now...
//          docStream.forEach(document -> {
//            vectorStore.add(List.of(document));
//          });
//
//        } catch (ParserConfigurationException e) {
//          throw new RuntimeException(e);
//        } catch (Exception e) {
//          throw new RuntimeException(e);
//        }
//      }
//    };
//  }

}
