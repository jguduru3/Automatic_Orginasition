package com.distributoros.security;

public record AuthUser(long userId, long tenantId, String role, String name) {
    public boolean isOwner() { return "OWNER".equals(role); }
}
