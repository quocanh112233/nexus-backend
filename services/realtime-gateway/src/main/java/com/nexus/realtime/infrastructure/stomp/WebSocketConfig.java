package com.nexus.realtime.infrastructure.stomp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

// Cấu hình STOMP: endpoint /ws không SockJS, broker trong bộ nhớ, heartbeat 10 s, xác thực CONNECT
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private final StompAuthChannelInterceptor authInterceptor;
  private final OutboundSubscriptionFilter outboundFilter;
  private final TaskScheduler heartbeatScheduler;
  private final String[] allowedOrigins;

  // Nhận interceptor xác thực, bộ lọc outbound, scheduler mặc định của broker (lazy) và origin
  public WebSocketConfig(
      StompAuthChannelInterceptor authInterceptor,
      OutboundSubscriptionFilter outboundFilter,
      @Lazy TaskScheduler messageBrokerTaskScheduler,
      @Value("${app.cors.allowed-origins}") String[] allowedOrigins) {
    this.authInterceptor = authInterceptor;
    this.outboundFilter = outboundFilter;
    this.heartbeatScheduler = messageBrokerTaskScheduler;
    this.allowedOrigins = allowedOrigins;
  }

  // Mở /ws cho các origin được phép
  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOrigins);
  }

  // Broker /topic và /queue, prefix app và user, heartbeat 10000,10000
  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry
        .enableSimpleBroker("/topic", "/queue")
        .setHeartbeatValue(new long[] {10000, 10000})
        .setTaskScheduler(heartbeatScheduler);
    registry.setApplicationDestinationPrefixes("/app");
    registry.setUserDestinationPrefix("/user");
  }

  // Gắn interceptor xác thực vào kênh nhận frame từ client
  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(authInterceptor);
  }

  // Gắn bộ lọc chặn frame của subscription đã bị thu hồi vào kênh gửi ra client
  @Override
  public void configureClientOutboundChannel(ChannelRegistration registration) {
    registration.interceptors(outboundFilter);
  }
}
