package fi.haagahelia.accounting.api;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fi.haagahelia.accounting.api.dto.HistoricalExchangeRateResponse;
import fi.haagahelia.accounting.service.HistoricalExchangeRateService;

@RestController
@RequestMapping("/api/exchange-rates/history")
public class HistoricalExchangeRateRestController {

    private final HistoricalExchangeRateService service;

    public HistoricalExchangeRateRestController(
            HistoricalExchangeRateService service) {

        this.service = service;
    }

    @GetMapping
    public HistoricalExchangeRateResponse getRates(
            @RequestParam("date")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date,
            @RequestParam("currencies")
            List<String> currencies) {

        return service.getRates(date, currencies);
    }
}