package org.cubexmc.ecobalancer.tax;

public class TaxRunState {
    private final boolean running;
    private final int operationId;
    private final String policyName;
    private final long startedAt;
    private final int totalPlayers;
    private final int processedPlayers;
    private final int affectedPlayers;
    private final double totalDeducted;
    private final TaxOperationType trigger;
    private final String senderName;

    public TaxRunState(boolean running, int operationId, String policyName, long startedAt, int totalPlayers,
            int processedPlayers, int affectedPlayers, double totalDeducted, TaxOperationType trigger,
            String senderName) {
        this.running = running;
        this.operationId = operationId;
        this.policyName = policyName;
        this.startedAt = startedAt;
        this.totalPlayers = totalPlayers;
        this.processedPlayers = processedPlayers;
        this.affectedPlayers = affectedPlayers;
        this.totalDeducted = totalDeducted;
        this.trigger = trigger;
        this.senderName = senderName;
    }

    public boolean isRunning() {
        return running;
    }

    public int getOperationId() {
        return operationId;
    }

    public String getPolicyName() {
        return policyName;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public int getTotalPlayers() {
        return totalPlayers;
    }

    public int getProcessedPlayers() {
        return processedPlayers;
    }

    public int getAffectedPlayers() {
        return affectedPlayers;
    }

    public double getTotalDeducted() {
        return totalDeducted;
    }

    public TaxOperationType getTrigger() {
        return trigger;
    }

    public String getSenderName() {
        return senderName;
    }
}
