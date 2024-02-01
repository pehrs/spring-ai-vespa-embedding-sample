package com.pehrs.spring.ai.rss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RssItem(// @JacksonXmlProperty
                      String title,
                      // @JacksonXmlProperty
                      String link,
                      // @JacksonXmlProperty
                      String guid) {

}
