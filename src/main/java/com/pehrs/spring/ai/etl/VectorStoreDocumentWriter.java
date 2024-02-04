package com.pehrs.spring.ai.etl;

import com.pehrs.spring.ai.vespa.VespaVectorStore;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class VectorStoreDocumentWriter implements ItemWriter<Document> {

  @Autowired
  VectorStore vectorStore;

  @Override
  public void write(Chunk<? extends Document> chunk) throws Exception {
    TextSplitter textSplitter = new TokenTextSplitter();
    List<Document> splitDocuments =
        textSplitter.apply(chunk.getItems().stream().collect(Collectors.toList()));
    vectorStore.add(splitDocuments);
  }
}
