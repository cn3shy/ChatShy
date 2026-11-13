package io.github.cn3shy.chatshy;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import io.github.cn3shy.chatshy.config.ChatShyProperties;

@SpringBootApplication
@EnableConfigurationProperties(ChatShyProperties.class)
@MapperScan("io.github.cn3shy.chatshy.mapper")
public class ChatShyApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChatShyApplication.class, args);
    }
}
