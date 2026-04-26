package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncforge.api.delivery.DeliveryTruthModel;
import org.junit.jupiter.api.Test;

class DeliveryTruthModelTest {
    @Test
    void namesCanonicalTruthAndDeliveryRoles() {
        assertThat(DeliveryTruthModel.DB_OPERATION_LOG).isEqualTo("CANONICAL_TRUTH");
        assertThat(DeliveryTruthModel.REDIS_STREAM).isEqualTo("DELIVERY_PIPE");
        assertThat(DeliveryTruthModel.WEBSOCKET_FANOUT).isEqualTo("LOCAL_DELIVERY");
        assertThat(DeliveryTruthModel.CLIENT_ACK).isEqualTo("CLIENT_PROGRESS");
        assertThat(DeliveryTruthModel.clientAckIsCanonicalTruth()).isFalse();
    }

    @Test
    void acceptedRejectedAndDuplicateOutboxInvariantsAreExplicit() {
        assertThat(DeliveryTruthModel.requiresOutboxIntent(true, false)).isTrue();
        assertThat(DeliveryTruthModel.mayCreateOutboxIntent(true, false)).isTrue();

        assertThat(DeliveryTruthModel.requiresOutboxIntent(false, false)).isFalse();
        assertThat(DeliveryTruthModel.mayCreateOutboxIntent(false, false)).isFalse();

        assertThat(DeliveryTruthModel.requiresOutboxIntent(true, true)).isFalse();
        assertThat(DeliveryTruthModel.mayCreateOutboxIntent(true, true)).isFalse();
    }
}
