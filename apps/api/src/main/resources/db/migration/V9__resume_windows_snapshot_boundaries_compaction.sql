alter table room_operations
    add column compacted boolean not null default false,
    add column compacted_at timestamptz,
    add column compaction_run_id uuid;

create index idx_room_operations_room_seq_active
    on room_operations(room_id, room_seq)
    where compacted = false;

create index idx_room_operations_room_compacted
    on room_operations(room_id, compacted, room_seq);

create table document_snapshot_text_atoms (
    snapshot_id uuid not null references document_snapshots(id) on delete cascade,
    room_id uuid not null references rooms(id),
    atom_id text not null,
    operation_id text not null,
    room_seq bigint not null,
    revision bigint not null,
    span_index int not null,
    anchor_atom_id text,
    content text not null,
    ordering_key text not null,
    tombstoned boolean not null default false,
    deleted_by_operation_id text,
    deleted_at_room_seq bigint,
    created_at timestamptz,
    updated_at timestamptz,
    captured_at timestamptz not null default now(),
    primary key (snapshot_id, atom_id),
    constraint ck_document_snapshot_text_atoms_room_seq check (room_seq > 0),
    constraint ck_document_snapshot_text_atoms_revision check (revision > 0),
    constraint ck_document_snapshot_text_atoms_span_index check (span_index >= 0),
    constraint ck_document_snapshot_text_atoms_deleted_room_seq check (deleted_at_room_seq is null or deleted_at_room_seq > 0),
    constraint ck_document_snapshot_text_atoms_content_not_null check (content is not null),
    constraint ck_document_snapshot_text_atoms_ordering_key_not_blank check (length(ordering_key) > 0)
);

create index idx_document_snapshot_text_atoms_snapshot_ordering
    on document_snapshot_text_atoms(snapshot_id, ordering_key);

create index idx_document_snapshot_text_atoms_snapshot_anchor
    on document_snapshot_text_atoms(snapshot_id, anchor_atom_id);

create table room_operation_compaction_runs (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    minimum_resumable_room_seq bigint not null,
    snapshot_room_seq bigint not null,
    compacted_count int not null,
    active_tail_count int not null,
    status text not null,
    created_at timestamptz not null default now(),
    constraint ck_room_operation_compaction_runs_minimum check (minimum_resumable_room_seq >= 0),
    constraint ck_room_operation_compaction_runs_snapshot check (snapshot_room_seq >= 0),
    constraint ck_room_operation_compaction_runs_counts check (compacted_count >= 0 and active_tail_count >= 0),
    constraint ck_room_operation_compaction_runs_status check (status in ('COMPLETED'))
);

create index idx_room_operation_compaction_runs_room_created
    on room_operation_compaction_runs(room_id, created_at desc);
