package com.pehrs.spring.ai.etl_tst;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pehrs.spring.ai.vespa.VespaVectorStore.VespaDoc;
import com.pehrs.spring.ai.vespa.VespaVectorStore.VespaEmbedding;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public record NewsRecord(
    @JsonProperty("news_id")
    String newsId,
    @JsonProperty("title")
    String title,
    @JsonProperty("abstract")
    String abstraction,
    @JsonProperty("url")
    String url,
    @JsonProperty("embedding")
    List<Double> embedding
    ) {

    static ObjectMapper mapper = new ObjectMapper();

    public NewsRecord withEmbedding(List<Double> embedding) {
        return new NewsRecord(
            this.newsId,
            this.title,
            this.abstraction,
            this.url,
            embedding
        );
    }

    public String toVespaInsertJson() throws JsonProcessingException {
        Map<String, Object> fields = new HashMap<>();
        fields.put("news_id", this.newsId);
        fields.put("title", this.title);
        fields.put("abstract", this.abstraction);
        fields.put("url", this.url);
        fields.put("embedding", new VespaEmbedding(this.embedding));
        VespaDoc vespaDoc = new VespaDoc(fields);
        return mapper.writeValueAsString(vespaDoc);
    }

}
