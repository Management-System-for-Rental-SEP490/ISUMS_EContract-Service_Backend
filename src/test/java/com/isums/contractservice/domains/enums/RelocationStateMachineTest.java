package com.isums.contractservice.domains.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RelocationStateMachine")
class RelocationStateMachineTest {

    @Nested
    @DisplayName("validate — allowed transitions")
    class Allowed {

        @Test
        @DisplayName("REQUESTED can move to QUOTED, APPROVED, REJECTED, CANCELLED, COMPLETED")
        void fromRequested() {
            for (RelocationRequestStatus next : List.of(
                    RelocationRequestStatus.QUOTED,
                    RelocationRequestStatus.APPROVED,
                    RelocationRequestStatus.REJECTED,
                    RelocationRequestStatus.CANCELLED,
                    RelocationRequestStatus.COMPLETED)) {
                assertThatCode(() ->
                        RelocationStateMachine.validate(RelocationRequestStatus.REQUESTED, next))
                        .as("REQUESTED -> %s", next)
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("QUOTED can move to APPROVED, REJECTED, CANCELLED")
        void fromQuoted() {
            for (RelocationRequestStatus next : List.of(
                    RelocationRequestStatus.APPROVED,
                    RelocationRequestStatus.REJECTED,
                    RelocationRequestStatus.CANCELLED)) {
                assertThatCode(() ->
                        RelocationStateMachine.validate(RelocationRequestStatus.QUOTED, next))
                        .as("QUOTED -> %s", next)
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("APPROVED can move to CONTRACT_CREATED, CANCELLED")
        void fromApproved() {
            for (RelocationRequestStatus next : List.of(
                    RelocationRequestStatus.CONTRACT_CREATED,
                    RelocationRequestStatus.CANCELLED)) {
                assertThatCode(() ->
                        RelocationStateMachine.validate(RelocationRequestStatus.APPROVED, next))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("CONTRACT_CREATED can move to ADDITIONAL_PAYMENT_PENDING, REFUND_PENDING, COMPLETED")
        void fromContractCreated() {
            for (RelocationRequestStatus next : List.of(
                    RelocationRequestStatus.ADDITIONAL_PAYMENT_PENDING,
                    RelocationRequestStatus.REFUND_PENDING,
                    RelocationRequestStatus.COMPLETED)) {
                assertThatCode(() ->
                        RelocationStateMachine.validate(RelocationRequestStatus.CONTRACT_CREATED, next))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("ADDITIONAL_PAYMENT_PENDING and REFUND_PENDING can both move to COMPLETED")
        void payAndRefundToCompleted() {
            assertThatCode(() ->
                    RelocationStateMachine.validate(
                            RelocationRequestStatus.ADDITIONAL_PAYMENT_PENDING,
                            RelocationRequestStatus.COMPLETED))
                    .doesNotThrowAnyException();
            assertThatCode(() ->
                    RelocationStateMachine.validate(
                            RelocationRequestStatus.REFUND_PENDING,
                            RelocationRequestStatus.COMPLETED))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("self-transition is a no-op (does not throw)")
        void selfTransitionAllowed() {
            for (RelocationRequestStatus s : RelocationRequestStatus.values()) {
                assertThatCode(() -> RelocationStateMachine.validate(s, s))
                        .as("self-transition %s", s)
                        .doesNotThrowAnyException();
            }
        }
    }

    @Nested
    @DisplayName("validate — disallowed transitions")
    class Disallowed {

        @Test
        @DisplayName("CONTRACT_CREATED cannot be CANCELLED (manager must cancel before contract)")
        void contractCreatedCannotCancel() {
            assertThatThrownBy(() -> RelocationStateMachine.validate(
                    RelocationRequestStatus.CONTRACT_CREATED,
                    RelocationRequestStatus.CANCELLED))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("CONTRACT_CREATED")
                    .hasMessageContaining("CANCELLED");
        }

        @Test
        @DisplayName("APPROVED cannot be REJECTED (review window already closed)")
        void approvedCannotReject() {
            assertThatThrownBy(() -> RelocationStateMachine.validate(
                    RelocationRequestStatus.APPROVED,
                    RelocationRequestStatus.REJECTED))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("REJECTED is terminal — no outgoing transition")
        void rejectedTerminal() {
            for (RelocationRequestStatus next : RelocationRequestStatus.values()) {
                if (next == RelocationRequestStatus.REJECTED) continue; // self-transition allowed
                assertThatThrownBy(() ->
                        RelocationStateMachine.validate(RelocationRequestStatus.REJECTED, next))
                        .as("REJECTED -> %s should throw", next)
                        .isInstanceOf(IllegalStateException.class);
            }
        }

        @Test
        @DisplayName("CANCELLED is terminal — no outgoing transition")
        void cancelledTerminal() {
            for (RelocationRequestStatus next : RelocationRequestStatus.values()) {
                if (next == RelocationRequestStatus.CANCELLED) continue;
                assertThatThrownBy(() ->
                        RelocationStateMachine.validate(RelocationRequestStatus.CANCELLED, next))
                        .isInstanceOf(IllegalStateException.class);
            }
        }

        @Test
        @DisplayName("COMPLETED is terminal — no outgoing transition")
        void completedTerminal() {
            for (RelocationRequestStatus next : RelocationRequestStatus.values()) {
                if (next == RelocationRequestStatus.COMPLETED) continue;
                assertThatThrownBy(() ->
                        RelocationStateMachine.validate(RelocationRequestStatus.COMPLETED, next))
                        .isInstanceOf(IllegalStateException.class);
            }
        }

        @Test
        @DisplayName("CONTRACT_CREATED cannot go back to APPROVED (no rewind)")
        void noRewind() {
            assertThatThrownBy(() -> RelocationStateMachine.validate(
                    RelocationRequestStatus.CONTRACT_CREATED,
                    RelocationRequestStatus.APPROVED))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("REQUESTED cannot jump to CONTRACT_CREATED (must go through APPROVED)")
        void noSkipApproval() {
            assertThatThrownBy(() -> RelocationStateMachine.validate(
                    RelocationRequestStatus.REQUESTED,
                    RelocationRequestStatus.CONTRACT_CREATED))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
