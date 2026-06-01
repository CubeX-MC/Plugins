package org.cubexmc.mountlicense.service;

public final class ReindexResult {

    private final int scanned;
    private final int recovered;

    public ReindexResult(int scanned, int recovered) {
        this.scanned = scanned;
        this.recovered = recovered;
    }

    public int scanned() { return scanned; }
    public int recovered() { return recovered; }
}
