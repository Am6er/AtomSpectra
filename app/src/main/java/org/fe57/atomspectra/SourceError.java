package org.fe57.atomspectra;

/**
 * The last failure a {@link SpectrumSource} reported. Transient until the source recovers, then dropped by the source.
 * A failed connect is terminal: the source does not retry on its own and waits for the user.
 */
final class SourceError {
    final int op;           // SpectrumSource.OP_*
    final int reason;       // SpectrumSource.REASON_*
    final String text;
    final long timeMillis;

    SourceError(int op, int reason, String text) {
        this.op = op;
        this.reason = reason;
        this.text = text;
        this.timeMillis = System.currentTimeMillis();
    }

    boolean isTerminal() {
        return this.op == SpectrumSource.OP_CONNECT;
    }

    boolean isPermission() {
        return this.reason == SpectrumSource.REASON_PERMISSION;
    }
}
