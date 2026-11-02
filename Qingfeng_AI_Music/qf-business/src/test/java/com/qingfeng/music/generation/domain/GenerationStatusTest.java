package com.qingfeng.music.generation.domain;

import com.qingfeng.music.common.exception.DomainException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.qingfeng.music.generation.domain.GenerationStatus.*;

class GenerationStatusTest {
    @Test void completedTaskCannotBeSubmittedAgain() {
        assertThrows(DomainException.class, () -> SUCCEEDED.transitionTo(SUBMITTING));
    }
    @Test void uncertainSubmissionMustBeReconciledBeforeRetry() {
        assertThrows(DomainException.class, () -> SUBMISSION_UNKNOWN.transitionTo(SUBMITTING));
        assertEquals(RUNNING, SUBMISSION_UNKNOWN.transitionTo(RUNNING));
    }
    @Test void savingMustNotTriggerAnotherGeneration() {
        assertThrows(DomainException.class, () -> SAVING.transitionTo(SUBMITTING));
        assertEquals(SUCCEEDED, SAVING.transitionTo(SUCCEEDED));
    }
}
