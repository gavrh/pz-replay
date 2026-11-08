package com.gavrh.pzreplay;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class WorldApplySummary {
    private final Set<String> warnedIdentities = new HashSet<>();
    private final List<String> warnings = new ArrayList<>();
    private long applied;
    private long recordedOnly;
    private long partial;
    private long skipped;
    private long missing;
    private long mismatched;
    private long unsupported;
    private long unknownKinds;
    private long reloadRequired;
    private long duplicate;

    public void applied() {
        applied(false);
    }

    public void applied(boolean partial) {
        applied++;
        if (partial) {
            partial();
        }
    }

    public void appliedWithFlags(int flags, boolean partial) {
        boolean recordedOnly = (flags & WorldLogCodec.FLAG_RECORDED_ONLY) != 0;
        boolean reloadRequired = (flags & WorldLogCodec.FLAG_RELOAD_REQUIRED) != 0;
        if (recordedOnly && reloadRequired) {
            recordedOnlyReloadRequired(partial);
        } else if (recordedOnly) {
            recordedOnly(partial);
        } else if (reloadRequired) {
            reloadRequired(partial);
        } else {
            applied(partial);
        }
    }

    public void recordedOnly() {
        recordedOnly(false);
    }

    public void recordedOnly(boolean partial) {
        recordedOnly++;
        if (partial) {
            partial();
        }
    }

    public void partial() {
        partial++;
    }

    public void duplicate() {
        duplicate++;
        skipped++;
    }

    public void missing() {
        missing++;
        skipped++;
    }

    public void mismatched() {
        mismatched++;
        skipped++;
    }

    public void unsupported() {
        unsupported++;
        skipped++;
    }

    public void unknownKind(int count) {
        unknownKinds += count;
        skipped += count;
    }

    public void reloadRequired() {
        reloadRequired(false);
    }

    public void reloadRequired(boolean partial) {
        reloadRequired++;
        if (partial) {
            partial();
        }
    }

    public void recordedOnlyReloadRequired() {
        recordedOnlyReloadRequired(false);
    }

    public void recordedOnlyReloadRequired(boolean partial) {
        recordedOnly++;
        reloadRequired++;
        if (partial) {
            partial();
        }
    }

    public boolean warnOnce(String identity, String message) {
        if (identity == null || identity.isBlank() || !warnedIdentities.add(identity)) {
            return false;
        }
        warnings.add(message);
        return true;
    }

    public void warn(String message) {
        if (message != null && !message.isBlank() && !warnings.contains(message)) {
            warnings.add(message);
        }
    }

    public long appliedCount() {
        return applied;
    }

    public long recordedOnlyCount() {
        return recordedOnly;
    }

    public long partialCount() {
        return partial;
    }

    public long skippedCount() {
        return skipped;
    }

    public long missingCount() {
        return missing;
    }

    public long mismatchedCount() {
        return mismatched;
    }

    public long unsupportedCount() {
        return unsupported;
    }

    public long unknownKindCount() {
        return unknownKinds;
    }

    public long reloadRequiredCount() {
        return reloadRequired;
    }

    public long duplicateCount() {
        return duplicate;
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public String text() {
        return "applied=" + applied
            + " recorded_only=" + recordedOnly
            + " partial=" + partial
            + " skipped=" + skipped
            + " missing=" + missing
            + " mismatched=" + mismatched
            + " unsupported=" + unsupported
            + " unknown=" + unknownKinds
            + " reload_required=" + reloadRequired
            + " duplicate=" + duplicate;
    }
}
