package com.syncforge.api.harness;

import java.util.List;

public record CollaborationScenario(long seed, List<ScriptedOperation> operations) {
}
