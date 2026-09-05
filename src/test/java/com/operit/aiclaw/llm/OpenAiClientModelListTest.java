package com.operit.aiclaw.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiClientModelListTest {
    private HttpServer server;
    private AtomicReference<String> authorization;

    @BeforeEach
    void startServer() throws IOException {
        authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void fetchesStandardDataArrayFromDerivedV1ModelsEndpoint() {
        server.createContext("/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("GET", exchange.getRequestMethod());
            respond(exchange, 200, """
                    {"data":[
                      {"id":"alpha","display_name":"Alpha Model"},
                      {"id":"beta"},
                      {"id":"alpha","display_name":"Ignored duplicate"}
                    ]}
                    """);
        });
        server.start();

        OpenAiClient client = client("", "test-key");

        assertEquals(baseUrl() + "/v1/models", client.modelsEndpoint());
        assertEquals(List.of(
                new OpenAiClient.ModelInfo("alpha", "Alpha Model"),
                new OpenAiClient.ModelInfo("beta", "beta")), client.listModels());
        assertEquals("Bearer test-key", authorization.get());
    }

    @Test
    void acceptsModelsArrayAndKeepsExistingV1BasePath() {
        server.createContext("/v1/models", exchange -> respond(exchange, 200,
                "{\"models\":[\"one\",{\"id\":\"two\",\"displayName\":\"Two\"}]}"));
        server.start();

        OpenAiClient client = client("/v1", null);

        assertEquals(baseUrl() + "/v1/models", client.modelsEndpoint());
        assertEquals(List.of(
                new OpenAiClient.ModelInfo("one", "one"),
                new OpenAiClient.ModelInfo("two", "Two")), client.listModels());
        assertEquals(null, authorization.get());
    }

    @Test
    void surfacesEndpointFailureWithoutLeakingRequestCredentials() {
        server.createContext("/v1/models", exchange -> respond(exchange, 403, "{\"error\":\"forbidden\"}"));
        server.start();

        LlmException error = assertThrows(LlmException.class, () -> client("", "secret-key").listModels());

        assertEquals(403, error.getStatusCode());
        assertEquals(true, error.getMessage().contains("model list API error 403"));
        assertEquals(false, error.getMessage().contains("secret-key"));
    }

    private OpenAiClient client(String suffix, String apiKey) {
        return new OpenAiClient(baseUrl() + suffix, apiKey, 5,
                new ThinkingConfig(), RequestStyle.OPENAI_GENERAL);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}