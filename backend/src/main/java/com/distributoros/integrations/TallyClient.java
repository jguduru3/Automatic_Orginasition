package com.distributoros.integrations;

public interface TallyClient {
    /** Posts one voucher import envelope. Returns the Tally voucher id/number; throws on failure. */
    String post(String xmlEnvelope, String fallbackNumber);
}
