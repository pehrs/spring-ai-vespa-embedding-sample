package com.pehrs.spring.ai.rss;


import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Stack;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.batch.item.ItemReader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.xml.sax.SAXException;
import reactor.netty.http.client.HttpClient;

public class RssXmlAiDocumentReader implements ItemReader<Document> {

  static final Logger log = LoggerFactory.getLogger(RssXmlAiDocumentReader.class);

  private final Stack<String> rssUrls;
  private final DocumentBuilder xmlBuilder;
  private final WebClient webClient;
  private final Histogram rssSourceHistogram;
  private final Histogram rssArticleHistogram;

  private Stack<Document> urlDocs = new Stack();

  private Stack<String> currentRssItemUrls = new Stack();

  public RssXmlAiDocumentReader(MetricRegistry metricRegistry, List<String> rssUrls)
      throws ParserConfigurationException {
    this.rssUrls = new Stack();
    this.rssUrls.addAll(rssUrls);
    this.xmlBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    this.webClient = WebClient.builder()
        .clientConnector(new ReactorClientHttpConnector(
            HttpClient.create().followRedirect(true)
        ))
        .exchangeStrategies(ExchangeStrategies.withDefaults())
        .build();

    this.rssSourceHistogram = metricRegistry.histogram("rss.source.ms");
    this.rssArticleHistogram = metricRegistry.histogram("rss.article.ms");
    metricRegistry.register("rss.articles.pending",
        (Gauge<Integer>) () -> currentRssItemUrls.size());
    metricRegistry.register("rss.sources.pending",
        (Gauge<Integer>) () -> urlDocs.size());
  }

  @Override
  public synchronized Document read()
      throws Exception {

    if (urlDocs.empty()) {
      String url = nextUrl();

      if (url == null) {
        return null;
      }
      try {
        log.trace("Reading RSS article: "+ url);
        long start = System.currentTimeMillis();
        Resource xmlResource = new UrlResource(url);
        TikaDocumentReader documentReader = new TikaDocumentReader(xmlResource);
        List<Document> documents =
            documentReader.get();
        this.rssArticleHistogram.update(System.currentTimeMillis() - start);
        urlDocs.addAll(documents);
      } catch (RuntimeException ex) {
        // Let's skip to the next url...
        return read();
      }
    }

    if (urlDocs.empty()) {
      return null;
    }

    return urlDocs.pop();
  }

  private String nextUrl() throws IOException, SAXException {
    if (currentRssItemUrls.empty()) {
      this.currentRssItemUrls = getNextListOfItems();
    }
    if (currentRssItemUrls.empty()) {
      return null;
    }
    return currentRssItemUrls.pop();
  }

  private static final XmlMapper xmlMapper = new XmlMapper();

  private static final String USER_AGENT = "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:122.0) Gecko/20100101 Firefox/122.0";

  static {
    xmlMapper.disable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    xmlMapper.enable(SerializationFeature.INDENT_OUTPUT);
  }

  private Stack<String> getNextListOfItems() throws IOException, SAXException {
    if (rssUrls.empty()) {
      return new Stack();
    }
    String rssUrl = rssUrls.pop();
    log.info("Reading RSS source: "+ rssUrl);
    long start = System.currentTimeMillis();
    String responseBody = webClient.get()
        .uri(rssUrl)
        .accept(MediaType.APPLICATION_RSS_XML)
        .retrieve().bodyToMono(String.class).block();
    this.rssSourceHistogram.update(System.currentTimeMillis() - start);

    if (responseBody == null) {
      return new Stack();
    }

    RssFeed rssFeed = xmlMapper.readValue(responseBody, RssFeed.class);
    Stack<String> itemUrls = new Stack<>();
    itemUrls.addAll(
        rssFeed.channel().items().stream()
            .map(rssItem -> rssItem.link() == null ? rssItem.guid() : rssItem.link())
            .collect(Collectors.toList())
    );

    return itemUrls;
  }
}
