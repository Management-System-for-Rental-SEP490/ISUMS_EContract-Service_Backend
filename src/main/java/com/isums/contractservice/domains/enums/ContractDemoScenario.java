package com.isums.contractservice.domains.enums;

public enum ContractDemoScenario {
    D60(60),
    D30(30),
    D14(14),
    D7(7),
    D3(3),
    D1(1),
    D0(0),
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
