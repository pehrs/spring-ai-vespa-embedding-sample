package com.pehrs.spring.ai.rss;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RssFeed(RssChannel channel) {

}