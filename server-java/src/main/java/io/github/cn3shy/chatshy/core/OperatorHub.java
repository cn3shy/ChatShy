package io.github.cn3shy.chatshy.core;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import io.github.cn3shy.chatshy.config.ChatShyProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/** 操作台实时通道：向所有在线管理端 SSE 连接广播事件。 */
@Service
public class OperatorHub {
    public interface HubClient {
        void write(String frame) throws IOException;
    }

    private final Set<HubClient> clients = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "chatshy-hub-ping");
        t.setDaemon(true);
        return t;
    });
    private final long pingMs;

    public OperatorHub(ChatShyProperties properties) {
        this.pingMs = properties.hubPingMs() > 0 ? properties.hubPingMs() : 20_000;
    }

    @PostConstruct
    public void startPing() {
        scheduler.scheduleAtFixedRate(() -> broadcast(HubEvent.ping()), pingMs, pingMs, TimeUnit.MILLISECONDS);
    }

    public void addClient(HubClient client) {
        clients.add(client);
    }

    public void removeClient(HubClient client) {
        clients.remove(client);
    }

    public int clientCount() {
        return clients.size();
    }

    public void broadcast(HubEvent event) {
        writeAll(event.toFrame());
    }

    private void writeAll(String frame) {
        for (HubClient client : clients) {
            try {
                client.write(frame);
            } catch (Exception e) {
                clients.remove(client);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
