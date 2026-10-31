package fi.haagahelia.accounting.api;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.api.dto.CreateTransferRequest;
import fi.haagahelia.accounting.api.dto.PageResponse;
import fi.haagahelia.accounting.api.dto.TransferResponse;
import fi.haagahelia.accounting.model.User;
import fi.haagahelia.accounting.repository.UserRepository;
import fi.haagahelia.accounting.service.TransferService;
import fi.haagahelia.accounting.service.TransferService.TransferResult;


@RestController
@RequestMapping("/api/transfers")
public class TransferRestController {

    private final TransferService transferService;
    private final UserRepository userRepository;

    public TransferRestController(TransferService transferService,
                                  UserRepository userRepository) {
        this.transferService = transferService;
        this.userRepository = userRepository;
    }

    @GetMapping
    public PageResponse<TransferResponse> getTransfers(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "User not found"
            );
        }

        Page<TransferResponse> result = transferService.getTransfers(
            currentUser, page, size
        );

        return new PageResponse<>(
            result.getContent(),
            result.getNumber(),
            result.getSize(),
            result.getTotalElements(),
            result.getTotalPages()
        );
    }

    @PostMapping
    public ResponseEntity<Void> createTransfer(
            @RequestBody CreateTransferRequest request,
            Authentication auth) {

        User currentUser = userRepository.findByUsername(auth.getName());

        if (currentUser == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "User not found"
            );
        }

        if (request.fromAccountId() == null || request.toAccountId() == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Source and destination accounts are required"
            );
        }

        TransferResult result = transferService.executeTransferWithAmounts(
            request.fromAccountId(),
            request.toAccountId(),
            request.amount(),
            request.targetAmount(),
            request.description(),
            currentUser,
            request.syncId()
        );

        return switch (result) {
            case SUCCESS ->
                ResponseEntity.status(HttpStatus.CREATED).build();

            case INVALID_ACCOUNTS ->
                throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Accounts are unavailable or access is denied"
                );

            case SAME_ACCOUNT ->
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Source and destination accounts must be different"
                );

            case DIFFERENT_CURRENCIES ->
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Destination amount is required for transfers between different currencies"
                );

            case INVALID_AMOUNT ->
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Amount must be greater than zero "
                        + "and have at most two decimal places"
                );

            case INVALID_TARGET_AMOUNT ->
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Destination amount must be positive, have at most two decimal places "
                        + "and equal the source amount for same-currency transfers"
                );

            case INSUFFICIENT_FUNDS ->
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Insufficient funds"
                );
        };
    }
}