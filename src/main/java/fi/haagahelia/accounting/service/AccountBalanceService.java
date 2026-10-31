package fi.haagahelia.accounting.service;

import org.springframework.stereotype.Service;

import fi.haagahelia.accounting.api.dto.ConvertedBalanceResponse;
import fi.haagahelia.accounting.api.dto.ExchangeRateSnapshot;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.AccountRepository;

@Service
public class AccountBalanceService {

    private final AccountRepository accountRepository;
    private final CurrencyConversionService conversionService;
    private final ExchangeRateService exchangeRateService;

    public AccountBalanceService(
            AccountRepository accountRepository,
            CurrencyConversionService conversionService,
            ExchangeRateService exchangeRateService) {

        this.accountRepository = accountRepository;
        this.conversionService = conversionService;
        this.exchangeRateService = exchangeRateService;
    }

    public ConvertedBalanceResponse getBalance(
            User currentUser,
            String targetCurrency) {

        String target = conversionService.normalizeCurrencyCode(
            targetCurrency
        );

        var amounts = accountRepository.sumBalancesByCurrency(
            currentUser
        );

        boolean conversionRequired = amounts.stream()
            .anyMatch(item ->
                item.amount().signum() != 0
                    && !target.equals(item.currencyCode())
            );

        if (conversionRequired) {
            ExchangeRateSnapshot snapshot =
                exchangeRateService.getLatestRates();

            return new ConvertedBalanceResponse(
                target,
                conversionService.convertTotal(
                    amounts,
                    target,
                    snapshot
                ),
                snapshot.fetchedAt()
            );
        }

        return new ConvertedBalanceResponse(
            target,
            conversionService.convertTotal(
                amounts,
                target,
                null
            ),
            null
        );
    }
}