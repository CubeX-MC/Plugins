package org.cubexmc.contract.gui;

import org.cubexmc.contract.model.ContractType;

/**
 * Mutable draft for the GUI creation wizard. Holds the fields a player fills in
 * before previewing and signing. {@link #validate} is a pure function so the
 * wizard's field/amount rules can be unit tested without a server.
 */
public final class CreateDraft {
    private final ContractType type;
    private String title;
    private String description;
    private Integer hours;
    private Double amount;
    private Double partnerStake;
    private String counterparty;
    private String mediator;

    public CreateDraft(ContractType type) {
        this.type = type;
    }

    public ContractType type() {
        return type;
    }

    public String title() {
        return title;
    }

    public void title(String title) {
        this.title = title;
    }

    public String description() {
        return description;
    }

    public void description(String description) {
        this.description = description;
    }

    public Integer hours() {
        return hours;
    }

    public void hours(Integer hours) {
        this.hours = hours;
    }

    public Double amount() {
        return amount;
    }

    public void amount(Double amount) {
        this.amount = amount;
    }

    public Double partnerStake() {
        return partnerStake;
    }

    public void partnerStake(Double partnerStake) {
        this.partnerStake = partnerStake;
    }

    public String counterparty() {
        return counterparty;
    }

    public void counterparty(String counterparty) {
        this.counterparty = counterparty;
    }

    public String mediator() {
        return mediator;
    }

    public void mediator(String mediator) {
        this.mediator = mediator;
    }

    public boolean needsCounterparty() {
        return type == ContractType.WAGER || type == ContractType.PARTNERSHIP;
    }

    public boolean needsPartnerStake() {
        return type == ContractType.PARTNERSHIP;
    }

    public boolean mediatorRequired() {
        return type == ContractType.WAGER;
    }

    /**
     * Returns the first blocking validation message, or {@code null} when the
     * draft is complete and within the configured limits.
     */
    public String validate(double minAmount, double maxAmount, int minHours, int maxHours) {
        if (title == null || title.isBlank()) {
            return "请先填写标题";
        }
        if (hours == null) {
            return "请先填写期限";
        }
        if (hours < minHours || hours > maxHours) {
            return "期限必须在 " + minHours + " 到 " + maxHours + " 小时之间";
        }
        if (amount == null) {
            return "请先填写金额";
        }
        if (amount < minAmount || amount > maxAmount) {
            return "金额必须在 " + minAmount + " 到 " + maxAmount + " 之间";
        }
        if (needsCounterparty() && (counterparty == null || counterparty.isBlank())) {
            return "请先填写对方玩家";
        }
        if (mediatorRequired() && (mediator == null || mediator.isBlank())) {
            return "请先填写仲裁者";
        }
        if (needsPartnerStake()) {
            if (partnerStake == null) {
                return "请先填写对方押注";
            }
            if (partnerStake < minAmount || partnerStake > maxAmount) {
                return "对方押注必须在 " + minAmount + " 到 " + maxAmount + " 之间";
            }
        }
        return null;
    }

    public boolean isReady(double minAmount, double maxAmount, int minHours, int maxHours) {
        return validate(minAmount, maxAmount, minHours, maxHours) == null;
    }
}
