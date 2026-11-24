package com.distributoros.integrations;

public class MockTallyClient implements TallyClient {
    @Override
    public String post(String xmlEnvelope, String fallbackNumber) {
        return fallbackNumber;
    }
}
