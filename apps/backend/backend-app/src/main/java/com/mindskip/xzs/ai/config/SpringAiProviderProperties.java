package com.mindskip.xzs.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.spring")
public record SpringAiProviderProperties(ProviderSource providerSource) {

    public SpringAiProviderProperties {
        providerSource = providerSource == null ? ProviderSource.DATABASE : providerSource;
    }

    public enum ProviderSource {
        DATABASE,
        ENVIRONMENT
    }
}
