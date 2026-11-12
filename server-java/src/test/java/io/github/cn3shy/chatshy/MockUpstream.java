package io.github.cn3shy.chatshy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 测试用的极简 OpenAI 兼容上游（原始 ServerSocket，避免额外依赖）。 */
public final class MockUpstream implements AutoCloseable {
    public record Req(String method, String path, Map<String, String> headers, String body) {
    }

    @FunctionalInterface
    public interface Handler {
        void handle(Req req, OutputStream out) throws IOException;
    }

    private final ServerSocket server;
    private final Thread thread;
    private final List<Req> requests = Collections.synchronizedList(new ArrayList<>());
    private volatile Handler handler = (req, out) -> writeJson(out, 500, "{\"error\":{\"message\":\"no handler\"}}");

    public MockUpstream() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        thread = Thread.ofVirtual().start(this::acceptLoop);
    }

    public int port() {
        return server.getLocalPort();
    }

    public String base() {
        return "http://127.0.0.1:" + port();
    }

    public List<Req> requests() {
        return requests;
    }

    public void setHandler(Handler handler) {
        this.handler = handler;
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                handle(socket);
            } catch (IOException e) {
                if (server.isClosed()) return;
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        String requestLine = readLine(in);
        if (requestLine == null) return;
        String[] parts = requestLine.split(" ");
        String method = parts.length > 0 ? parts[0] : "";
        String path = parts.length > 1 ? parts[1] : "";

        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(),
                        line.substring(colon + 1).trim());
            }
        }
        int length = 0;
        try {
            length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        } catch (NumberFormatException ignored) {
            // 0
        }
        String body = new String(in.readNBytes(length), StandardCharsets.UTF_8);
        Req request = new Req(method, path, headers, body);
        requests.add(request);
        try {
            handler.handle(request, out);
            out.flush();
        } catch (RuntimeException e) {
            writeJson(out, 500, "{\"error\":{\"message\":\"mock failure\"}}");
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') buffer.write(c);
        }
        if (!any && buffer.size() == 0 && c == -1) return null;
        return buffer.toString(StandardCharsets.ISO_8859_1);
    }

    public static void writeJson(OutputStream out, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + status + " OK\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    public static void writeSseHead(OutputStream out) throws IOException {
        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    public static void writeSseData(OutputStream out, String json) throws IOException {
        out.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public static String contentChunk(String text) {
        return "{\"choices\":[{\"index\":0,\"delta\":{\"content\":"
                + quote(text) + "}}]}";
    }

    public static String quote(String text) {
        try {
            return io.github.cn3shy.chatshy.core.Json.MAPPER.writeValueAsString(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
