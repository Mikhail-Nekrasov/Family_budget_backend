package fi.haagahelia.accounting.client;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import fi.haagahelia.accounting.api.dto.ExchangeRateQuote;

import tools.jackson.databind.json.JsonMapper;

@Component
public class FrankfurterClient {

    private final HttpClient httpClient;
    private final JsonMapper jsonMapper;
    private final URI ratesUri;

    public FrankfurterClient(
            JsonMapper jsonMapper,
            @Value("${app.exchange-rates.url:https://api.frankfurter.dev/v2/rates?base=EUR}")
            String ratesUrl) {

        this.jsonMapper = jsonMapper;
        this.ratesUri = URI.create(ratesUrl);

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    public List<ExchangeRateQuote> fetchLatestRates() {
        return fetchRates(ratesUri, null);
    }

    public List<ExchangeRateQuote> fetchRatesForDate(LocalDate date) {
        if (date.isAfter(LocalDate.now(ZoneOffset.UTC))) {
            throw new IllegalArgumentException(
                "Exchange rate date must not be in the future"
            );
        }

        String separator = ratesUri.getRawQuery() == null
            ? "?"
            : "&";

        URI historicalUri = URI.create(
            ratesUri.toString() + separator + "date=" + date
        );

        return fetchRates(historicalUri, date);
    }

    public List<ExchangeRateQuote> fetchRatesForCurrencies(
            LocalDate date,
            List<String> currencyCodes) {

        if (date.isAfter(LocalDate.now(ZoneOffset.UTC))) {
            throw new IllegalArgumentException(
                "Exchange rate date must not be in the future"
            );
        }

        Set<String> selected = new TreeSet<>();

        for (String code : currencyCodes) {
            if (code == null || !code.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException(
                    "Invalid currency code"
                );
            }

            selected.add(code);
        }

        if (selected.isEmpty()) {
            throw new IllegalArgumentException(
                "At least one currency is required"
            );
        }

        String separator = ratesUri.getRawQuery() == null
            ? "?"
            : "&";

        URI selectedUri = URI.create(
            ratesUri.toString()
                + separator
                + "date=" + date
                + "&quotes=" + String.join(",", selected)
        );

        return fetchRates(selectedUri, date);
    }

    private List<ExchangeRateQuote> fetchRates(
            URI uri,
            @Nullable LocalDate requestedDate) {

        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("Accept", "application/json")
            .GET()
            .build();

        HttpResponse<String> response;

        try {
            response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                "Exchange rate request was interrupted",
                exception
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Exchange rate provider is unavailable",
                exception
            );
        }

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                "Exchange rate provider returned HTTP "
                    + response.statusCode()
            );
        }

        return parseRates(response.body(), requestedDate);
    }

    private List<ExchangeRateQuote> parseRates(
            String body,
            @Nullable LocalDate requestedDate) {

        try {
            ProviderRate[] rows = jsonMapper.readValue(
                body,
                ProviderRate[].class
            );

            if (rows == null || rows.length == 0) {
                throw new IllegalArgumentException(
                    "Exchange rate list is empty"
                );
            }

            List<ExchangeRateQuote> result = new ArrayList<>();
            Set<String> currencies = new HashSet<>();

            for (ProviderRate row : rows) {
                if (row == null) {
                    throw new IllegalArgumentException(
                        "Exchange rate row is missing"
                    );
                }

                if (!"EUR".equals(row.base())) {
                    throw new IllegalArgumentException(
                        "Exchange rate base must be EUR"
                    );
                }

                String currencyCode = row.quote();

                if (currencyCode == null
                        || !currencyCode.matches("[A-Z]{3}")) {
                    throw new IllegalArgumentException(
                        "Invalid currency code"
                    );
                }

                BigDecimal rate = row.rate();

                if (rate == null || rate.signum() <= 0) {
                    throw new IllegalArgumentException(
                        "Exchange rate must be positive"
                    );
                }

                if ("EUR".equals(currencyCode)
                        && rate.compareTo(BigDecimal.ONE) != 0) {
                    throw new IllegalArgumentException(
                        "EUR exchange rate must equal one"
                    );
                }

                if (row.date() == null) {
                    throw new IllegalArgumentException(
                        "Exchange rate date is missing"
                    );
                }

                LocalDate rateDate = LocalDate.parse(row.date());

                if (requestedDate != null
                        && rateDate.isAfter(requestedDate)) {
                    throw new IllegalArgumentException(
                        "Exchange rate date is after the requested date"
                    );
                }

                if (!currencies.add(currencyCode)) {
                    throw new IllegalArgumentException(
                        "Duplicate exchange rate currency"
                    );
                }

                result.add(new ExchangeRateQuote(
                    currencyCode,
                    rate,
                    rateDate
                ));
            }

            return List.copyOf(result);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                "Exchange rate provider returned invalid data",
                exception
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProviderRate(
        String date,
        String base,
        String quote,
        BigDecimal rate
    ) {
    }
}