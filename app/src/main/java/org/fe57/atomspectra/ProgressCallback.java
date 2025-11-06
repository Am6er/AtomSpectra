package org.fe57.atomspectra;

public interface ProgressCallback<T> {
    void accept(T t);
}