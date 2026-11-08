package com.nexus.common.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Cấu hình relay outbox, bind từ nexus.messaging.relay
@ConfigurationProperties("nexus.messaging.relay")
public record RelayProperties(
    @DefaultValue("false") boolean enabled,
    String exchange,
    @DefaultValue("100") int batchSize,
    @DefaultValue("500ms") Duration interval,
    @DefaultValue("5s") Duration confirmTimeout,
    @DefaultValue("7d") Duration retention) {}
