package fi.haagahelia.accounting.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fi.haagahelia.accounting.api.dto.ExchangeRateSnapshot;
import fi.haagahelia.accounting.service.ExchangeRateService;

@RestController
@RequestMapping("/api/exchange-rates")
public class ExchangeRateRestController {

    private final ExchangeRateService exchangeRateService;

    public ExchangeRateRestController(
            ExchangeRateService exchangeRateService) {

        this.exchangeRateService = exchangeRateService;
    }

    @GetMapping
    public ExchangeRateSnapshot getRates() {
        return exchangeRateService.getLatestRates();
    }
}