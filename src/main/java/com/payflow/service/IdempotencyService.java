package com.payflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.dto.PaymentResponse;
import com.payflow.entity.IdempotencyRecord;
import com.payflow.exception.DuplicateRequestException;
import com.payflow.repository.IdempotencyRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class IdempotencyService {

    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private static final Duration KEY_TTL = Duration.ofHours(24);

    public IdempotencyService(IdempotencyRepository idempotencyRepository, ObjectMapper objectMapper) {
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Checks if a request with this key has already been executed or is currently in flight.
     * @return Optional containing cached PaymentResponse if completed, or empty if new.
     * @throws DuplicateRequestException if request is currently IN_PROGRESS.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PaymentResponse> reserveOrGet(String idempotencyKey, UUID userId) {
        Optional<IdempotencyRecord> existing = idempotencyRepository.findByIdempotencyKeyAndUserId(idempotencyKey, userId);

        if (existing.isPresent()) {
            IdempotencyRecord record = existing.get();
            if (record.getStatus() == IdempotencyRecord.Status.IN_PROGRESS) {
                throw new DuplicateRequestException(
                    "Payment request with Idempotency-Key '" + idempotencyKey + "' is currently in-progress. Please retry shortly."
                );
            }
            if (record.getStatus() == IdempotencyRecord.Status.COMPLETED && record.getResponseBody() != null) {
                try {
                    PaymentResponse cached = objectMapper.readValue(record.getResponseBody(), PaymentResponse.class);
                    return Optional.of(cached);
                } catch (JsonProcessingException e) {
                    throw new RuntimeException("Failed to deserialize cached idempotency response", e);
                }
            }
        }

        try {
            // Reserve key atomically
            IdempotencyRecord record = new IdempotencyRecord(idempotencyKey, userId, Instant.now().plus(KEY_TTL));
            idempotencyRepository.saveAndFlush(record);
            return Optional.empty();
        } catch (DataIntegrityViolationException ex) {
            // Caught simultaneous concurrent insert race condition
            throw new DuplicateRequestException(
                "Payment request with Idempotency-Key '" + idempotencyKey + "' was just initiated concurrently."
            );
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String idempotencyKey, UUID userId, PaymentResponse response, int statusCode) {
        idempotencyRepository.findByIdempotencyKeyAndUserId(idempotencyKey, userId).ifPresent(record -> {
            try {
                record.setStatus(IdempotencyRecord.Status.COMPLETED);
                record.setResponseStatusCode(statusCode);
                record.setResponseBody(objectMapper.writeValueAsString(response));
                idempotencyRepository.save(record);
            } catch (JsonProcessingException e) {
                record.setStatus(IdempotencyRecord.Status.COMPLETED);
                idempotencyRepository.save(record);
            }
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String idempotencyKey, UUID userId) {
        idempotencyRepository.findByIdempotencyKeyAndUserId(idempotencyKey, userId)
            .ifPresent(record -> {
                record.setStatus(IdempotencyRecord.Status.FAILED);
                idempotencyRepository.save(record);
            });
    }
}
