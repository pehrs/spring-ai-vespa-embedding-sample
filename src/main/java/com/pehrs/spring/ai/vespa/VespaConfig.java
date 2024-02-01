package com.pehrs.spring.ai.vespa;

import com.pehrs.spring.ai.util.YamlPropertySourceFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "yaml")
@PropertySource(value = "classpath:vespa.yaml", factory = YamlPropertySourceFactory.class)
public class VespaConfig {

  @Value("${vespa.container.endpoint}")
  String containerEndpoint; // http://localhost:8080
  @Value("${vespa.container.queryUri}")
  String queryUri; // /search/
  @Value("${vespa.namespace}")
  String namespace; // llm
  @Value("${vespa.docType}")
  String docType; // llm

  // FIXME: This should be extracted from the Spring AI stuff...
  @Value("${vespa.embeddingSize}")
  int embeddingSize; // 4096
  @Value("${vespa.rankingName}")
  String rankingName; // recommendation
  @Value("${vespa.rankingInputName}")
  String rankingInputName; // q_embedding
  @Value("${vespa.embeddingFieldName}")
  String embeddingFieldName; // embedding
  @Value("${vespa.contentFieldName}")
  String contentFieldName; // content

  @Value("${vespa.targetHits}")
  int targetHits;

}
