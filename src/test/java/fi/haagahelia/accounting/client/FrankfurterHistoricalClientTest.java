package fi.haagahelia.accounting.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class FrankfurterHistoricalClientTest {

    private HttpServer server;
    private FrankfurterClient client;

    private volatile String responseBody;
    private volatile String receivedQuery;

    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        responseBody = "[]";
        receivedQuery = "";

        server = HttpServer.create(
            new InetSocketAddress("127.0.0.1", 0),
            0
        );

        server.createContext("/v2/rates", exchange -> {
            requestCount.incrementAndGet();
            receivedQuery = exchange.getRequestURI().getRawQuery();

            byte[] body = responseBody.getBytes(
                StandardCharsets.UTF_8
            );

            exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json"
            );

            exchange.sendResponseHeaders(200, body.length);

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
    void requestsHistoricalRatesForSpecifiedDate() {
        responseBody = """
            [
                {
                    "date": "2025-01-18",
                    "base": "EUR",
                    "quote": "USD",
                    "rate": 1.0299
                },
                {
                    "date": "2025-01-18",
                    "base": "EUR",
                    "quote": "RUB",
                    "rate": 105.56
                }
            ]
            """;

        var rates = client.fetchRatesForDate(
            LocalDate.of(2025, 1, 18)
        );

        assertEquals(
            "base=EUR&date=2025-01-18",
            receivedQuery
        );
        assertEquals(2, rates.size());

        var rub = rates.stream()
            .filter(rate -> rate.currencyCode().equals("RUB"))
            .findFirst()
            .orElseThrow();

        assertEquals(
            0,
            new BigDecimal("105.56").compareTo(rub.rate())
        );
        assertEquals(
            LocalDate.of(2025, 1, 18),
            rub.rateDate()
        );
    }

    @Test
    void requestsOnlySelectedCurrencies() {
        responseBody = """
            [
                {
                    "date": "2025-01-18",
                    "base": "EUR",
                    "quote": "RUB",
                    "rate": 105.56
                },
                {
                    "date": "2025-01-18",
                    "base": "EUR",
                    "quote": "USD",
                    "rate": 1.0299
                }
            ]
            """;

        var rates = client.fetchRatesForCurrencies(
            LocalDate.of(2025, 1, 18),
            List.of("USD", "RUB")
        );

        assertEquals(
            "base=EUR&date=2025-01-18&quotes=RUB,USD",
            receivedQuery
        );
        assertEquals(2, rates.size());
    }

    @Test
    void preservesEarlierRateDateReturnedByProvider() {
        responseBody = """
            [
                {
                    "date": "2025-01-17",
                    "base": "EUR",
                    "quote": "USD",
                    "rate": 1.0299
                }
            ]
            """;

        var rates = client.fetchRatesForDate(
            LocalDate.of(2025, 1, 18)
        );

        assertEquals(
            LocalDate.of(2025, 1, 17),
            rates.get(0).rateDate()
        );
    }

    @Test
    void rejectsRateDatedAfterRequestedDate() {
        responseBody = """
            [
                {
                    "date": "2025-01-19",
                    "base": "EUR",
                    "quote": "USD",
                    "rate": 1.03
                }
            ]
            """;

        assertThrows(
            IllegalStateException.class,
            () -> client.fetchRatesForDate(
                LocalDate.of(2025, 1, 18)
            )
        );
    }

    @Test
    void rejectsFutureRequestWithoutCallingProvider() {
        LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC)
            .plusDays(1);

        assertThrows(
            IllegalArgumentException.class,
            () -> client.fetchRatesForDate(tomorrow)
        );

        assertEquals(0, requestCount.get());
    }

    @Test
    void rejectsEmptyHistoricalResponse() {
        responseBody = "[]";

        assertThrows(
            IllegalStateException.class,
            () -> client.fetchRatesForDate(
                LocalDate.of(2025, 1, 18)
            )
        );
    }
}