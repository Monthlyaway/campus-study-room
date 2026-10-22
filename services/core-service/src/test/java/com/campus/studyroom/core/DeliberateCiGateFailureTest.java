package com.campus.studyroom.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/** Intentional negative CI evidence. This branch must never be merged. */
class DeliberateCiGateFailureTest {
    @Test
    void failedVerificationMustBlockMergeAndDeployment() {
        fail("Intentional CI gate probe: do not merge this verification branch");
    }
}
