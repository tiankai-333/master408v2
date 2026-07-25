package com.mindskip.xzs.ai.config;

import io.micrometer.observation.ObservationRegistry;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ai.rag.vector.enabled", havingValue = "true")
@ConditionalOnBean(EmbeddingModel.class)
public class SpringAiQdrantConfiguration {

    @Bean(destroyMethod = "close")
    QdrantClient qdrantClient(
            @Value("${spring.ai.vectorstore.qdrant.host:127.0.0.1}") String host,
            @Value("${spring.ai.vectorstore.qdrant.port:6334}") int port,
            @Value("${spring.ai.vectorstore.qdrant.use-tls:false}") boolean useTls,
            @Value("${spring.ai.vectorstore.qdrant.api-key:}") String apiKey) {
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(host, port, useTls);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.withApiKey(apiKey);
        }
        return new QdrantClient(builder.build());
    }

    @Bean
    VectorStore qdrantVectorStore(
            QdrantClient qdrantClient,
            EmbeddingModel embeddingModel,
            ObservationRegistry observationRegistry,
            @Value("${spring.ai.vectorstore.qdrant.collection-name:xzs_408_chunks_spring_v1}")
            String collectionName,
            @Value("${spring.ai.vectorstore.qdrant.initialize-schema:true}") boolean initializeSchema) {
        return QdrantVectorStore.builder(qdrantClient, embeddingModel)
                .collectionName(collectionName)
                .initializeSchema(initializeSchema)
                .batchingStrategy(new TokenCountBatchingStrategy())
                .observationRegistry(observationRegistry)
                .build();
    }
}
