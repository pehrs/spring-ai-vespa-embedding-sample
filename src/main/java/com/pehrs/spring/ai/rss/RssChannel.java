package com.pehrs.spring.ai.rss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonMerge;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RssChannel(
    @JacksonXmlElementWrapper(useWrapping = false) @JacksonXmlProperty(localName = "item") @JsonMerge
    List<RssItem> items) {

}
