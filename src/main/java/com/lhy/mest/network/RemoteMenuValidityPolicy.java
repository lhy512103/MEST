package com.lhy.mest.network;

import java.util.function.BooleanSupplier;

/** A revoked remote menu cannot silently fall back to local validity or regain its lease. */
final class RemoteMenuValidityPolicy {
    private boolean revoked;

    static boolean sameTarget(Object playerLevel, Object targetLevel, boolean loaded,
            Object expectedBlockEntity, Object actualBlockEntity, boolean removed) {
        return playerLevel != null && playerLevel == targetLevel && loaded
                && expectedBlockEntity != null && expectedBlockEntity == actualBlockEntity && !removed;
    }

    boolean evaluate(BooleanSupplier authorize, BooleanSupplier original, BooleanSupplier exemptDistance) {
        try {
            boolean allowed = !revoked && authorize.getAsBoolean();
            // Even a revoked menu executes its own predicate, but without a distance exemption.
            boolean valid = allowed ? exemptDistance.getAsBoolean() : original.getAsBoolean();
            revoked = !allowed || !valid;
            return !revoked;
        } catch (RuntimeException | Error exception) {
            revoked = true;
            throw exception;
        }
    }
}
