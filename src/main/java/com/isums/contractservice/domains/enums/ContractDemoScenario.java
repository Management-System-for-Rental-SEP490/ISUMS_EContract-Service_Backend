package com.isums.contractservice.domains.enums;

public enum ContractDemoScenario {
    D60(60),
    D30(30),
    D1(1),
    EXPIRED(-1),
    CUSTOM(null);

    private final Integer daysRemaining;

    ContractDemoScenario(Integer daysRemaining) {
        this.daysRemaining = daysRemaining;
    }

    public Integer daysRemaining() {
        return daysRemaining;
    }
}
