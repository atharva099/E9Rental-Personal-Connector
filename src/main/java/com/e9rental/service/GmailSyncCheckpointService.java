package com.e9rental.service;

import com.e9rental.connector.UnifiedRepository;
import com.e9rental.model.SearchRecord;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Applies an already-retrieved history batch without owning a Gmail client or making provider calls. */
@Service
public class GmailSyncCheckpointService {
    public record HistoryBatch(String startHistoryId, String endHistoryId, List<SearchRecord> changedRecords, List<String> removedMessageIds) {
        public HistoryBatch {
            Objects.requireNonNull(endHistoryId);
            changedRecords = List.copyOf(Objects.requireNonNull(changedRecords));
            removedMessageIds = List.copyOf(Objects.requireNonNull(removedMessageIds));
        }
        public HistoryBatch(String startHistoryId,String endHistoryId,List<SearchRecord> changedRecords) {
            this(startHistoryId,endHistoryId,changedRecords,List.of());
        }
    }

    public record Result(boolean replayed, String historyId, UnifiedRepository.Result records) {}

    private final UnifiedRepository repository;

    public GmailSyncCheckpointService(UnifiedRepository repository) {
        this.repository = repository;
    }

    public Result apply(HistoryBatch batch) {
        Objects.requireNonNull(batch);
        var result = repository.applyGmailSyncBatch(batch.startHistoryId(), batch.endHistoryId(), batch.changedRecords(),batch.removedMessageIds());
        return new Result(result.replayed(), result.historyId(), result.records());
    }

    public boolean deleteUserRequested(String messageId) {
        return repository.deletePersonalGmailRecord(messageId);
    }

    public Optional<String> currentHistoryId() { return repository.gmailSyncHistoryId(); }
    public boolean isTombstoned(String messageId) { return repository.isPersonalGmailTombstoned(messageId); }
}
