package io.github.cn3shy.chatshy.config;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 同一个 Spring Boot 进程额外监听豆奶端口（默认 127.0.0.1:8099）。 */
@Configuration
public class TomcatExtraConnectorConfig {

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> dounaiConnector(ChatShyProperties properties) {
        return factory -> {
            ChatShyProperties.Dounai dounai = properties.dounai();
            if (dounai == null || !dounai.enabled()) return;
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(dounai.port());
            connector.setProperty("address", dounai.host());
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}
