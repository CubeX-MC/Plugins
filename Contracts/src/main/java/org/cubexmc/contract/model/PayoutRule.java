package org.cubexmc.contract.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 描述某个 condition 触发时,从哪个 source 把多少份额转给哪个 recipient.
 * sharePercent 是源 stake 池的百分比(0-100)。
 */
public final class PayoutRule {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final PayoutCondition condition;
    private final ParticipantRole source;
    private final PayoutRecipient recipient;
    private final BigDecimal sharePercent;

    public PayoutRule(PayoutCondition condition, ParticipantRole source,
                      PayoutRecipient recipient, BigDecimal sharePercent) {
        this.condition = condition;
        this.source = source;
        this.recipient = recipient;
        this.sharePercent = sharePercent;
    }

    public PayoutCondition condition() {
        return condition;
    }

    public ParticipantRole source() {
        return source;
    }

    public PayoutRecipient recipient() {
        return recipient;
    }

    public BigDecimal sharePercent() {
        return sharePercent;
    }

    public BigDecimal applyTo(BigDecimal sourceAmount) {
        return sourceAmount.multiply(sharePercent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("condition", condition.name());
        map.put("source", source.name());
        map.put("recipient", recipient.toMap());
        map.put("share-percent", sharePercent.toPlainString());
        return map;
    }

    public static PayoutRule fromMap(Map<?, ?> map) {
        PayoutCondition condition = PayoutCondition.valueOf(Objects.toString(map.get("condition"), "SUCCESS"));
        ParticipantRole source = ParticipantRole.valueOf(Objects.toString(map.get("source"), "OWNER"));
        PayoutRecipient recipient;
        Object rec = map.get("recipient");
        if (rec instanceof Map<?, ?> recMap) {
            recipient = PayoutRecipient.fromMap(recMap);
        } else {
            recipient = PayoutRecipient.systemSink();
        }
        Object share = map.get("share-percent");
        BigDecimal value = share == null ? BigDecimal.ZERO : new BigDecimal(share.toString());
        return new PayoutRule(condition, source, recipient, value);
    }
}
