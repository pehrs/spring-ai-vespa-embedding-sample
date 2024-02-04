# spring-ai-vespa-embedding-sample

This repo is the result of me experimenting with running LLM models 
[spring-ai](https://docs.spring.io/spring-ai/reference/), 
[ollama](https://ollama.ai/) and [vespa](https://vespa.ai/). 
I wanted to run everything locally and not rely on any online services. 

This is a simple RAG Spring AI service running everything locally 
that uses Vespa as the VectorStore and an ollama model 
for building embeddings and prompting.

The repo has two (spring-boot) applications:

- [PopulateVespaVectorStore](src/main/java/com/pehrs/spring/ai/etl/PopulateVespaVectorStore.java) - 
Batch job that will get a number of news articles via RSS feeds and insert them into Vespa for 
the RAG calling the ollama to generate the embedding vector.
- [RagSampleService](src/main/java/com/pehrs/spring/ai/service/RagSampleService.java) - Service that will use Vespa to do a similarity search 
to provide set of documents for the PromptTemplate. The service uses this [template](src/main/resources/rag-prompt-template.st).


![Overview](spring-ai-vespa-embedding-sample.svg)

This code is built on-top of these samples:
- https://github.com/habuma/spring-ai-rag-example
- https://github.com/chenkunyun/spring-boot-assembly/tree/master
- https://docs.vespa.ai/en/tutorials/news-1-getting-started.html


Remember that spring-ai is still in development. 
Please check out these for updates:

- https://docs.spring.io/spring-ai/reference/
- https://github.com/spring-projects/spring-ai
- https://repo.spring.io/ui/native/snapshot/org/springframework/ai/spring-ai/

## Build

```shell
# if you use asdf then set jdk version to 17+
asdf local java corretto-17.0.6.10.1

# Results go into target/spring-ai-vespa-embedding-sample-0.0.1-SNAPSHOT-assembly/
mvn clean package
```

## Runtime Requirements

### Ollama model running locally

Install [ollama](https://ollama.ai/download) 

The [default configuration](src/main/resources/vespa.yaml) is using the [mistral llm](https://ollama.ai/library/mistral):
```shell
ollama pull mistral:latest
```

To list your local ollama models:
```shell
ollama list

# For more details on the models do:
curl -s localhost:11434/api/tags | jq .
```

### Vespa

#### Start Vespa cluster

You need to start a Vespa version 8 cluster:

```shell
docker run --detach \
  --name vespa \
  --hostname vespa-tutorial \
  --publish 8080:8080 \
  --publish 19071:19071 \
  --publish 19092:19092 \
  --publish 19050:19050 \
  vespaengine/vespa:8
```

Note: the 19050 port is not absolutely necessary, but has a nice 
[status page](http://localhost:19050/clustercontroller-status/v1/llm) for the Vespa cluster once you have your Vespa doc-types in place.

#### Deploy application
Install the vespa-cli if needed:
```shell
brew install vespa-cli
```

Run from the root of this repo:
```shell
vespa deploy --wait 300 vespa
```
If you used the above docker command to expose the 19050 
port then you can monitor the Cluster status on this page:
http://127.0.0.1:19050/clustercontroller-status/v1/llm


#### Stopping Vespa 

To kill (and delete all data from) the Vespa cluster just:
```shell
docker rm -f vespa
```


## Usage

The examples below are using bash scripts to start the 
applications as the "normal" way of building a spring-boot application, 
with the `spring-boot-maven-plugin` plugin, does not allow you 
to have multiple applications. 
So I'm using the `maven-assembly-plugin` to build a distribution with start scripts.

NOTE: The scripts have only been tested on Linux (Ubuntu 22.04) so your mileage might vary. 
You can always start the applications in Intellij, if you use that.

### Populate Vespa with your favorite news

```shell
./target/spring-ai-vespa-embedding-sample-0.0.1-SNAPSHOT-assembly/bin/populate-vespa-cluster.sh \
   http://www.svt.se/nyheter/rss.xml
```

### Start the RAG-Service

```shell
./target/spring-ai-vespa-embedding-sample-0.0.1-SNAPSHOT-assembly/bin/rag-service.sh
```

Once the service is up and running then you can ask a question:

```shell
curl localhost:8082/ask \
  -H "Content-type: application/json" \
  -d '{"question": "What are the top 5 news?"}'
```

## Configuration

### Vespa 

If you need to change the vespa config please make sure that your 
[vespa.yaml](src/main/resources/vespa.yaml) config aligns 
with the [vespa schema](vespa/schemas/embeddings.sd) deployed

### Ollama

If you need to change the ollama config please make sure 
your [application.properties](src/main/resources/application.properties) 
align with your downloaded model (`ollama list`)

## Misc

The image above is created using [PlantUML](https://plantuml.com/command-line) 
from the [spring-ai-vespa-embedding-sample.puml](spring-ai-vespa-embedding-sample.puml) file. 

## Stats

Running embeddings and inserts in parallel does not not yield any 
significant performance improvement as the contention 
is in the GPU cores. 

### Parallel calls to embedder and vespa (Apache http5 async calls to vespa)

Execution took 5 min 53.966 sec

```

-- Histograms ------------------------------------------------------------------
name             COUNT   MIN     MAX     MEAN    STDDEV  P50     P75     P95     P98     P99     P99.9  
embedding.ms     557     719     11756   10696.77 787.41  10891.00 11242.00 11414.00 11474.00 11514.00 11751.00
insert.ms        557     6       490     240.28  142.41  236.00  362.00  463.00  481.00  483.00  490.00 
rss.article.ms   140     89      1990    324.03  187.31  289.00  379.00  764.00  816.00  816.00  1990.00
rss.source.ms    3       169     457     418.07  89.40   457.00  457.00  457.00  457.00  457.00  457.00 

-- Meters ----------------------------------------------------------------------
NAME             COUNT   MEAN    1m      5m      15m    
insert.rps       557     1.58    4.34    1.49    0.82   


2024-02-03 10:37:36.122 [NONE] [main] INFO  com.zaxxer.hikari.HikariDataSource - HikariPool-1 - Shutdown initiated...
2024-02-03 10:37:36.124 [NONE] [main] INFO  com.zaxxer.hikari.HikariDataSource - HikariPool-1 - Shutdown completed.
 
```

### Spring batch

Execution took 5 min 45.225 sec


```
-- Histograms ------------------------------------------------------------------
name             COUNT   MIN     MAX     MEAN    STDDEV  P50     P75     P95     P98     P99     P99.9  
embedding.ms     539     188     2765    1439.03 610.28  1320.00 1936.00 2597.00 2645.00 2680.00 2753.00
insert.ms        539     6       31      10.10   1.44    10.00   11.00   12.00   12.00   13.00   16.00  
rss.article.ms   139     83      1697    355.14  249.96  277.00  334.00  922.00  1123.00 1697.00 1697.00
rss.source.ms    3       76      376     297.84  78.97   318.00  318.00  376.00  376.00  376.00  376.00 

-- Meters ----------------------------------------------------------------------
NAME             COUNT   MEAN    1m      5m      15m    
insert.rps       539     1.57    1.62    1.09    0.50   

```


### Single thread

Execution took 6 min 14.874 sec

```
-- Histograms ------------------------------------------------------------------
name             COUNT   MIN     MAX     MEAN    STDDEV  P50     P75     P95     P98     P99     P99.9  
embedding.ms     536     90      853     591.00  155.90  650.00  674.00  702.00  713.00  716.00  718.00 
insert.ms        536     6       24      9.38    1.49    9.00    10.00   11.00   12.00   12.00   17.00  
rss.article.ms   139     83      2297    333.73  317.82  267.00  334.00  717.00  1994.00 1994.00 2297.00
rss.source.ms    3       84      448     217.15  22.01   217.00  217.00  217.00  217.00  217.00  448.00 

-- Meters ----------------------------------------------------------------------
NAME             COUNT   MEAN    1m      5m      15m    
insert.rps       536     1.43    1.44    1.31    1.15   
```