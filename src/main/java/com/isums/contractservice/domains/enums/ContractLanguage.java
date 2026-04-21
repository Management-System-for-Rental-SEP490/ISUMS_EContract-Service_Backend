package com.isums.contractservice.domains.enums;

public enum ContractLanguage {
    VI,
    VI_EN,
    VI_JA;

    public String secondaryCode() {
        return switch (this) {
            case VI -> null;
            case VI_EN -> "en";
            case VI_JA -> "ja";
        };
    }

    public boolean isBilingual() {
        return this != VI;
    }
}
