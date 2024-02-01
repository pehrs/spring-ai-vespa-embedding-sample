package com.pehrs.spring.ai.etl;

import com.pehrs.spring.ai.rss.RssXmlAiDocumentReader;
import java.util.Arrays;
import java.util.List;
import javax.xml.parsers.ParserConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class BatchConfig  {

  @Bean
  public ItemReader<Document> reader() throws ParserConfigurationException {

    List<String> rssFeeds = List.of(
        "http://www.svt.se/nyheter/rss.xml"
    );
    String rssFeedsProp = System.getProperty("rss.feeds");
    if(rssFeedsProp != null) {
      rssFeeds = Arrays.stream(rssFeedsProp.split(",")).toList();
    }
    System.out.println("\n=========================\nLoading news from " + rssFeeds+"\n=========================\n");
    return new RssXmlAiDocumentReader(rssFeeds);
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
  public Step importDocsToVespa(JobRepository jobRepository,
      PlatformTransactionManager transactionManager,
      ItemReader<Document> reader,
      ItemWriter<Document> writer) {
    return new StepBuilder("importDocsToVespa", jobRepository)
        .<Document, Document> chunk(10, transactionManager)
        .reader(reader)
        // .processor(processor())
        .writer(writer)
        .build();
  }

}
