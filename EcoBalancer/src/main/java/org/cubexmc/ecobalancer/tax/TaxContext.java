package org.cubexmc.ecobalancer.tax;

import org.cubexmc.ecobalancer.utils.AnalysisFilters;

public class TaxContext {
    private final int operationId;
    private final String policyName;
    private final TaxOperationType operationType;
    private final long currentTime;
    private final boolean batchRun;
    private final AnalysisFilters.FilterCriteria criteria;

    public TaxContext(int operationId, String policyName, TaxOperationType operationType, long currentTime,
            boolean batchRun, AnalysisFilters.FilterCriteria criteria) {
        this.operationId = operationId;
        this.policyName = policyName;
        this.operationType = operationType;
        this.currentTime = currentTime;
        this.batchRun = batchRun;
        this.criteria = criteria;
    }

    public int getOperationId() {
        return operationId;
    }

    public String getPolicyName() {
        return policyName;
    }

    public TaxOperationType getOperationType() {
        return operationType;
    }

    public long getCurrentTime() {
        return currentTime;
    }

    public boolean isBatchRun() {
        return batchRun;
    }

    public AnalysisFilters.FilterCriteria getCriteria() {
        return criteria;
    }
}
