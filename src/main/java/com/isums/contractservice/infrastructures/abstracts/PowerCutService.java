package com.isums.contractservice.infrastructures.abstracts;

import java.util.UUID;

public interface PowerCutService {

    void confirmPowerCut(UUID contractId, UUID actorId);
}
