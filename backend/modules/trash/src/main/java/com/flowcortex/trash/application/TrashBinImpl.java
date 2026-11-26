package com.flowcortex.trash.application;

import com.flowcortex.trash.api.TrashBin;
import com.flowcortex.trash.api.TrashRecord;
import com.flowcortex.trash.domain.TrashKeys;
import com.flowcortex.trash.domain.TrashText;
import com.flowcortex.trash.infrastructure.TrashEntryStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Deliberately not {@code @Transactional}: a mandatory-propagation proxy would refuse with a Spring transaction
 * exception, where the contract promises {@link IllegalStateException}. The store joins the caller's transaction.
 */
@Component
class TrashBinImpl implements TrashBin {

    private final TrashEntryStore store;

    TrashBinImpl(TrashEntryStore store) {
        this.store = store;
    }

    @Override
    public void record(TrashRecord record) {
        requireTransaction("record");
        require(record != null, "record is required");
        require(TrashKeys.validType(record.type()), "type is not a valid key: " + record.type());
        require(TrashKeys.validItemId(record.itemId()), "item id is not valid: " + record.itemId());
        require(TrashKeys.validLocationCode(record.locationCode()),
                "location code is not valid: " + record.locationCode());
        require(record.ownerId() != null, "owner is required");
        require(record.deletedBy() != null, "deleter is required");
        String name = TrashText.displayName(record.name()).orElse(null);
        store.upsert(record, name, TrashText.key(name));
    }

    @Override
    public void forget(String type, String itemId) {
        requireTransaction("forget");
        store.deleteByItem(type, itemId);
    }

    private static void requireTransaction(String operation) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("TrashBin." + operation + " must run inside the caller's transaction");
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
