package com.syncforge.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncforge.api.text.application.TextConvergenceService;
import com.syncforge.api.text.model.TextAtom;
import com.syncforge.api.text.store.TextConvergenceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class StableTextIdentityIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    TextConvergenceRepository repository;

    private final TextConvergenceService service = new TextConvergenceService(null);

    @Test
    void persistsStableAtomIdentityAndOrderingMetadata() {
        Fixture fixture = fixture();
        String atomId = service.stableAtomId("op-stable", 0);

        boolean inserted = repository.insertAtom(fixture.roomId(), atomId, "op-stable", 1, 1, 0,
                null, "hello", "00000000000000000001:op-stable:0000");

        assertThat(inserted).isTrue();
        TextAtom atom = repository.findAtom(fixture.roomId(), atomId).orElseThrow();
        assertThat(atom.atomId()).isEqualTo("op-stable:0");
        assertThat(atom.roomSeq()).isEqualTo(1);
        assertThat(atom.revision()).isEqualTo(1);
        assertThat(atom.anchorAtomId()).isNull();
        assertThat(atom.orderingKey()).isEqualTo("00000000000000000001:op-stable:0000");
        assertThat(repository.listRoomAtoms(fixture.roomId())).extracting(TextAtom::atomId).containsExactly(atomId);
    }

    @Test
    void duplicateAtomIdentityIsSafelyIgnored() {
        Fixture fixture = fixture();
        String atomId = service.stableAtomId("op-duplicate", 0);

        assertThat(repository.insertAtom(fixture.roomId(), atomId, "op-duplicate", 1, 1, 0,
                null, "hello", "00000000000000000001:op-duplicate:0000")).isTrue();
        assertThat(repository.insertAtom(fixture.roomId(), atomId, "op-duplicate", 1, 1, 0,
                null, "hello", "00000000000000000001:op-duplicate:0000")).isFalse();

        assertThat(repository.countRoomAtoms(fixture.roomId())).isEqualTo(1);
    }
}
