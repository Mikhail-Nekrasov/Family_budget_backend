package fi.haagahelia.accounting.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;

class FrankfurterClientTest {

    private HttpServer server;
    private FrankfurterClient client;

    private volatile int responseStatus;
    private volatile String responseBody;
    private volatile String receivedQuery;

    @BeforeEach
    void setUp() throws IOException {
        responseStatus = 200;
        responseBody = "[]";
        receivedQuery = "";

        server = HttpServer.create(
            new InetSocketAddress("127.0.0.1", 0),
            0
        );

        server.createContext("/v2/rates", exchange -> {
            receivedQuery = exchange.getRequestURI().getRawQuery();

            byte[] body = responseBody.getBytes(
                StandardCharsets.UTF_8
            );

            exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json"
            );

            exchange.sendResponseHeaders(
                responseStatus,
                body.length
            );

            try (var output = exchange.getResponseBody()) {
                output.write(body);
            } finally {
                exchange.close();
            }
        });

        server.start();

        String url = "http://127.0.0.1:"
            + server.getAddress().getPort()
            + "/v2/rates?base=EUR";

        client = new FrankfurterClient(
            JsonMapper.builder().build(),
            url
        );
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void loadsRatesIncludingRubAndPreservesEachRateDate() {
        responseBody = """
            [
                {
                    "date": "2026-10-30",
                    "base": "EUR",
                    "quote": "EUR",
                    "rate": 1.0
                },
                {
                    "date": "2026-10-30",
                    "base": "EUR",
                    "quote": "USD",
                    "rate": 1.12345678
                },
                {
                    "date": "2026-10-29",
                    "base": "EUR",
                    "quote": "RUB",
                    "rate": 90.14,
                    "extraField": "ignored"
                }
            ]
            """;

        var rates = client.fetchLatestRates();

        assertEquals("base=EUR", receivedQuery);
        assertEquals(3, rates.size());

        var rub = rates.stream()
            .filter(rate -> rate.currencyCode().equals("RUB"))
            .findFirst()
            .orElseThrow();

        assertEquals(
            0,
            new BigDecimal("90.14").compareTo(rub.rate())
        );
        assertEquals(
            LocalDate.of(2026, 10, 29),
            rub.rateDate()
        );

        var usd = rates.stream()
            .filter(rate -> rate.currencyCode().equals("USD"))
            .findFirst()
            .orElseThrow();

        assertEquals(
            0,
            new BigDecimal("1.12345678").compareTo(usd.rate())
        );
    }

    @Test
    void rejectsProviderFailure() {
        responseStatus = 503;
        responseBody = """
            {"message": "Temporarily unavailable"}
            """;

        assertThrows(
            IllegalStateException.class,
            () -> client.fetchLatestRates()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "null", "not-json"})
    void rejectsEmptyOrMalformedResponse(String body) {
        responseBody = body;

        assertThrows(
            IllegalStateException.class,
            () -> client.fetchLatestRates()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "null"})
    void rejectsInvalidRate(String rate) {
        responseBody = """
            [
                {
                    "date": "2026-10-30",
                    "base": "EUR",
                    "quote": "RUB",
                    "rate": %s
                }
            ]
            """.formatted(rate);

        assertThrows(
            IllegalStateException.class,
            () -> client.fetchLatestRates()
        );
    }

    @Test
    void rejectsUnexpectedBaseCurrency() {
        responseBody = """
            [
                {
                    "date": "2026-10-30",
                    "base": "USD",
                    "quote": "RUB",
                    "rate": 80.00
                }
            ]
            """;

        assertThrows(
            IllegalStateException.class,
            () -> client.fetchLatestRates()
        );
    }

    @Test
    void rejectsDuplicateCurrencies() {
        responseBody = """
            [
                {
                    "date": "2026-10-30",
                    "base": "EUR",
                    "quote": "RUB",
                    "rate": 90.14
                },
                {
                    "date": "2026-10-30",
                    "base": "EUR",
                    "quote": "RUB",
                    "rate": 91.00
                }
            ]
            """;

        assertThrows(
            IllegalStateException.class,
            () -> client.fetchLatestRates()
        );
    }

    @Test
    void rejectsInvalidDate() {
        responseBody = """
            [
                {
                    "date": "invalid-date",
                    "base": "EUR",
                    "quote": "RUB",
                    "rate": 90.14
                }
            ]
            """;

        assertThrows(
            IllegalStateException.class,
            () -> client.fetchLatestRates()
        );
    }
}