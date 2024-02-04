package com.pehrs.spring.ai.vespa;

import com.pehrs.spring.ai.util.YamlPropertySourceFactory;
import java.lang.reflect.Field;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
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

  @Value("${vespa.threadPoolSize}")
  int threadPoolSize;

  @Override
  public String toString() {
    return "VespaConfig{" +
        "containerEndpoint='" + containerEndpoint + '\'' +
        ", queryUri='" + queryUri + '\'' +
        ", namespace='" + namespace + '\'' +
        ", docType='" + docType + '\'' +
        ", embeddingSize=" + embeddingSize +
        ", rankingName='" + rankingName + '\'' +
        ", rankingInputName='" + rankingInputName + '\'' +
        ", embeddingFieldName='" + embeddingFieldName + '\'' +
        ", contentFieldName='" + contentFieldName + '\'' +
        ", targetHits=" + targetHits +
        ", threadPoolSize=" + threadPoolSize +
        '}';
  }

  /**
   * Non-spring way to get the config
   */
  public static VespaConfig fromClassPath(String path) {
    Resource res = new ClassPathResource("vespa.yaml");
    return fromResource(res);
  }

  public static VespaConfig fromResource(Resource res) {
    YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
    factory.setResources(res);
    Properties properties = factory.getObject();
    try {
      VespaConfig vespaConfig = new VespaConfig();
      bindProperties(properties, vespaConfig);
      return vespaConfig;
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * FIXME: We should reuse the spring implementation somehow
   */
  static void bindProperties(Properties props, Object object) throws IllegalAccessException
  {
    for(Field field  : object.getClass().getDeclaredFields())
    {
      if (field.isAnnotationPresent(Value.class))
      {
        Value bind = field.getAnnotation(Value.class);
        // FIXME: This is a HACK!!!
        String propertyName = bind.value().replace("${", "").replace("}", "");
        String propValue = props.getProperty(propertyName);
        field.setAccessible(true);
        if(field.getType() == Integer.class || field.getType() == int.class) {
          field.set(object, Integer.parseInt(propValue));
        } else if(field.getType() == Long.class || field.getType() == long.class) {
          field.set(object, Long.parseLong(propValue));
        } else if(field.getType() == Double.class || field.getType() == double.class) {
          field.set(object, Double.parseDouble(propValue));
        } else if(field.getType() == Float.class || field.getType() == float.class) {
          field.set(object, Float.parseFloat(propValue));
        } else {
          field.set(object, propValue);
        }
      }
    }
  }
}
