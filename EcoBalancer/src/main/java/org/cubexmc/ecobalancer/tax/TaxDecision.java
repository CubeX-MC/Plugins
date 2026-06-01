package org.cubexmc.ecobalancer.tax;

public class TaxDecision {
    private final double oldBalance;
    private final double requestedDeduction;
    private final double actualDeduction;
    private final double newBalance;
    private final TaxDecisionResult result;
    private final String reason;

    public TaxDecision(double oldBalance, double requestedDeduction, double actualDeduction, double newBalance,
            TaxDecisionResult result, String reason) {
        this.oldBalance = oldBalance;
        this.requestedDeduction = requestedDeduction;
        this.actualDeduction = actualDeduction;
        this.newBalance = newBalance;
        this.result = result;
        this.reason = reason;
    }

    public double getOldBalance() {
        return oldBalance;
    }

    public double getRequestedDeduction() {
        return requestedDeduction;
    }

    public double getActualDeduction() {
        return actualDeduction;
    }

    public double getNewBalance() {
        return newBalance;
    }

    public TaxDecisionResult getResult() {
        return result;
    }

    public String getReason() {
        return reason;
    }
}
