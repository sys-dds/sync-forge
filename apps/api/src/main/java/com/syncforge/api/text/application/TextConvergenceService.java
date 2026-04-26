package com.syncforge.api.text.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.syncforge.api.documentstate.model.AppliedTextOperation;
import com.syncforge.api.operation.model.OperationRecord;
import com.syncforge.api.shared.BadRequestException;
import com.syncforge.api.text.model.CollaborativeTextOperation;
import com.syncforge.api.text.model.TextAnchor;
import com.syncforge.api.text.model.TextAtom;
import com.syncforge.api.text.model.TextAtomId;
import com.syncforge.api.text.store.TextConvergenceRepository;
import org.springframework.stereotype.Service;

@Service
public class TextConvergenceService {
    private final TextConvergenceRepository repository;

    public TextConvergenceService(TextConvergenceRepository repository) {
        this.repository = repository;
    }

    public CollaborativeTextOperation parseAndValidate(String operationType, Map<String, Object> operation) {
        CollaborativeTextOperation textOperation = CollaborativeTextOperation.fromPayload(operationType, operation);
        textOperation.validate();
        return textOperation;
    }

    public boolean supports(String operationType) {
        return "TEXT_INSERT_AFTER".equals(operationType) || "TEXT_DELETE_ATOMS".equals(operationType);
    }

    public AppliedTextOperation previewApply(UUID roomId, String operationType, Map<String, Object> operation) {
        CollaborativeTextOperation textOperation = parseAndValidate(operationType, operation);
        if ("TEXT_INSERT_AFTER".equals(operationType)) {
            requireAnchorExists(roomId, textOperation.anchor());
        } else {
            requireTargetsExist(roomId, textOperation.targetAtomIds());
        }
        return new AppliedTextOperation(operationType, canonicalPayload(textOperation), previewVisibleText(roomId, textOperation));
    }

    public AppliedTextOperation applyAccepted(OperationRecord operation) {
        CollaborativeTextOperation textOperation = parseAndValidate(operation.operationType(), operation.operation());
        if ("TEXT_INSERT_AFTER".equals(operation.operationType())) {
            requireAnchorExists(operation.roomId(), textOperation.anchor());
            String atomId = stableAtomId(operation.operationId(), 0);
            String anchorAtomId = textOperation.anchor().isStart() ? null : textOperation.anchor().atomId();
            repository.insertAtom(
                    operation.roomId(),
                    atomId,
                    operation.operationId(),
                    operation.roomSeq(),
                    operation.resultingRevision(),
                    0,
                    anchorAtomId,
                    textOperation.content(),
                    orderingKey(operation.roomSeq(), operation.operationId(), 0));
        } else {
            requireTargetsExist(operation.roomId(), textOperation.targetAtomIds());
            repository.markTombstoned(operation.roomId(), textOperation.targetAtomIds(), operation.operationId(), operation.roomSeq());
        }
        return new AppliedTextOperation(operation.operationType(), canonicalPayload(textOperation), materializeVisibleText(operation.roomId()));
    }

    public String materializeVisibleText(UUID roomId) {
        return materialize(repository.listRoomAtoms(roomId));
    }

    public ReplayProjection replay(List<OperationRecord> operations) {
        Map<String, ReplayAtom> atoms = new LinkedHashMap<>();
        for (OperationRecord operation : operations) {
            CollaborativeTextOperation textOperation = parseAndValidate(operation.operationType(), operation.operation());
            if ("TEXT_INSERT_AFTER".equals(operation.operationType())) {
                String anchorAtomId = textOperation.anchor().isStart() ? null : textOperation.anchor().atomId();
                if (anchorAtomId != null && !atoms.containsKey(anchorAtomId)) {
                    throw new BadRequestException("MISSING_TEXT_ANCHOR", "anchorAtomId does not exist");
                }
                String atomId = stableAtomId(operation.operationId(), 0);
                atoms.putIfAbsent(atomId, new ReplayAtom(
                        atomId,
                        operation.operationId(),
                        operation.roomSeq(),
                        operation.resultingRevision(),
                        0,
                        anchorAtomId,
                        textOperation.content(),
                        false));
            } else if ("TEXT_DELETE_ATOMS".equals(operation.operationType())) {
                for (String atomId : textOperation.targetAtomIds()) {
                    ReplayAtom atom = atoms.get(atomId);
                    if (atom == null) {
                        throw new BadRequestException("MISSING_TEXT_ATOM", "atomId does not exist");
                    }
                    atoms.put(atomId, atom.tombstone());
                }
            }
        }
        String content = materializeReplayAtoms(new ArrayList<>(atoms.values()));
        OperationRecord last = operations.isEmpty() ? null : operations.get(operations.size() - 1);
        return new ReplayProjection(
                content,
                last == null ? 0 : last.roomSeq(),
                last == null ? 0 : last.resultingRevision(),
                last == null ? null : last.id(),
                operations.size());
    }

    public String stableAtomId(String operationId, int spanIndex) {
        return TextAtomId.fromOperation(operationId, spanIndex).value();
    }

    private String previewVisibleText(UUID roomId, CollaborativeTextOperation operation) {
        List<TextAtom> atoms = new ArrayList<>(repository.listRoomAtoms(roomId));
        atoms.add(new TextAtom(
                roomId,
                "__preview__:0",
                "__preview__",
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                0,
                operation.type() == com.syncforge.api.text.model.TextOperationType.TEXT_INSERT_AFTER && operation.anchor().isStart()
                        ? null
                        : operation.type() == com.syncforge.api.text.model.TextOperationType.TEXT_INSERT_AFTER
                                ? operation.anchor().atomId()
                                : null,
                operation.content() == null ? "" : operation.content(),
                orderingKey(Long.MAX_VALUE, "__preview__", 0),
                operation.type() == com.syncforge.api.text.model.TextOperationType.TEXT_DELETE_ATOMS,
                null,
                null,
                null,
                null));
        if (operation.type() == com.syncforge.api.text.model.TextOperationType.TEXT_DELETE_ATOMS) {
            List<TextAtom> previewAtoms = atoms.stream()
                    .map(atom -> operation.targetAtomIds().contains(atom.atomId())
                            ? new TextAtom(atom.roomId(), atom.atomId(), atom.operationId(), atom.roomSeq(), atom.revision(),
                                    atom.spanIndex(), atom.anchorAtomId(), atom.content(), atom.orderingKey(), true,
                                    atom.deletedByOperationId(), atom.deletedAtRoomSeq(), atom.createdAt(), atom.updatedAt())
                            : atom)
                    .toList();
            return materialize(previewAtoms);
        }
        return materialize(atoms);
    }

    private void requireAnchorExists(UUID roomId, TextAnchor anchor) {
        if (anchor.isStart()) {
            return;
        }
        if (!repository.atomExists(roomId, anchor.atomId())) {
            throw new BadRequestException("MISSING_TEXT_ANCHOR", "anchorAtomId does not exist");
        }
    }

    private void requireTargetsExist(UUID roomId, List<String> atomIds) {
        for (String atomId : atomIds) {
            if (!repository.atomExists(roomId, atomId)) {
                throw new BadRequestException("MISSING_TEXT_ATOM", "atomId does not exist");
            }
        }
    }

    private Map<String, Object> canonicalPayload(CollaborativeTextOperation operation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (operation.type() == com.syncforge.api.text.model.TextOperationType.TEXT_INSERT_AFTER) {
            payload.put("anchorAtomId", operation.anchor().atomId());
            payload.put("text", operation.content());
        } else {
            payload.put("atomIds", operation.targetAtomIds());
        }
        return payload;
    }

    private String orderingKey(long roomSeq, String operationId, int spanIndex) {
        return "%020d:%s:%04d".formatted(roomSeq, operationId, spanIndex);
    }

    private String materialize(List<TextAtom> atoms) {
        Map<String, List<TextAtom>> childrenByAnchor = atoms.stream()
                .collect(Collectors.groupingBy(
                        atom -> atom.anchorAtomId() == null ? TextAnchor.START : atom.anchorAtomId(),
                        LinkedHashMap::new,
                        Collectors.toCollection(ArrayList::new)));
        childrenByAnchor.values().forEach(children -> children.sort(Comparator
                .comparingLong(TextAtom::roomSeq)
                .thenComparing(TextAtom::operationId)
                .thenComparingInt(TextAtom::spanIndex)
                .thenComparing(TextAtom::atomId)));
        StringBuilder visible = new StringBuilder();
        appendChildren(TextAnchor.START, childrenByAnchor, visible);
        return visible.toString();
    }

    private String materializeReplayAtoms(List<ReplayAtom> atoms) {
        Map<String, List<ReplayAtom>> childrenByAnchor = new HashMap<>();
        for (ReplayAtom atom : atoms) {
            childrenByAnchor.computeIfAbsent(atom.anchorAtomId() == null ? TextAnchor.START : atom.anchorAtomId(),
                    ignored -> new ArrayList<>()).add(atom);
        }
        childrenByAnchor.values().forEach(children -> children.sort(Comparator
                .comparingLong(ReplayAtom::roomSeq)
                .thenComparing(ReplayAtom::operationId)
                .thenComparingInt(ReplayAtom::spanIndex)
                .thenComparing(ReplayAtom::atomId)));
        StringBuilder visible = new StringBuilder();
        appendReplayChildren(TextAnchor.START, childrenByAnchor, visible, new HashSet<>());
        return visible.toString();
    }

    private void appendReplayChildren(
            String anchorAtomId,
            Map<String, List<ReplayAtom>> childrenByAnchor,
            StringBuilder visible,
            Set<String> visited) {
        for (ReplayAtom atom : childrenByAnchor.getOrDefault(anchorAtomId, List.of())) {
            if (!visited.add(atom.atomId())) {
                throw new BadRequestException("INVALID_TEXT_OPERATION", "text atom cycle detected");
            }
            if (!atom.tombstoned()) {
                visible.append(atom.content());
            }
            appendReplayChildren(atom.atomId(), childrenByAnchor, visible, visited);
        }
    }

    private void appendChildren(String anchorAtomId, Map<String, List<TextAtom>> childrenByAnchor, StringBuilder visible) {
        for (TextAtom atom : childrenByAnchor.getOrDefault(anchorAtomId, List.of())) {
            if (!atom.tombstoned()) {
                visible.append(atom.content());
            }
            appendChildren(atom.atomId(), childrenByAnchor, visible);
        }
    }

    public record ReplayProjection(String content, long roomSeq, long revision, UUID lastOperationId, int operationsReplayed) {
    }

    private record ReplayAtom(
            String atomId,
            String operationId,
            long roomSeq,
            long revision,
            int spanIndex,
            String anchorAtomId,
            String content,
            boolean tombstoned
    ) {
        ReplayAtom tombstone() {
            return new ReplayAtom(atomId, operationId, roomSeq, revision, spanIndex, anchorAtomId, content, true);
        }
    }
}
