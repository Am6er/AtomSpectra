package org.fe57.atomspectra;

public class CancellationToken {
    private boolean isCancelled = false;

    public void cancel() {
        isCancelled = true;
    }

    public boolean isCancelled() {
        return isCancelled;
    }

}
