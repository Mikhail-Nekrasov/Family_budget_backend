package fi.haagahelia.accounting.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import fi.haagahelia.accounting.repository.SyncCommandRepository;
import fi.haagahelia.accounting.repository.SyncCommandRepository.CompletedCommand;
import tools.jackson.databind.json.JsonMapper;

@Service
public class SyncCommandService {

    private final SyncCommandRepository syncCommandRepository;
    private final JsonMapper jsonMapper;

    public SyncCommandService(
            SyncCommandRepository syncCommandRepository,
            JsonMapper jsonMapper) {

        this.syncCommandRepository = syncCommandRepository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public CompletedCommand execute(
            Long userId,
            UUID commandId,
            String operation,
            Object normalizedRequest,
            int responseStatus,
            Supplier<?> action) {

        String requestHash = sha256(
            operation + "\n"
                + jsonMapper.writeValueAsString(normalizedRequest)
        );

        syncCommandRepository.lockUser(userId);

        var previous = syncCommandRepository.findCompleted(
            userId, commandId
        );

        if (previous.isPresent()) {
            CompletedCommand completed = previous.get();

            if (!operation.equals(completed.operation())
                    || !requestHash.equals(completed.requestHash())) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Command ID is already used for a different request"
                );
            }

            return completed;
        }

        Object response = action.get();

        CompletedCommand completed = new CompletedCommand(
            operation,
            requestHash,
            responseStatus,
            jsonMapper.writeValueAsString(response)
        );

        syncCommandRepository.saveCompleted(
            userId, commandId, completed
        );

        return completed;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)
                )
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                "SHA-256 is unavailable",
                exception
            );
        }
    }
}