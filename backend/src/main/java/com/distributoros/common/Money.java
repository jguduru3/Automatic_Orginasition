package com.distributoros.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    private Money() {}

    public static BigDecimal rupees(BigDecimal v) { return v.setScale(0, RoundingMode.HALF_UP); }
    public static BigDecimal paise(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }
    public static BigDecimal of(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(o.toString());
    }
}
