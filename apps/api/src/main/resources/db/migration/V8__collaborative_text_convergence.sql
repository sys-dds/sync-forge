create table collaborative_text_atoms (
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
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (room_id, atom_id),
    constraint ck_collaborative_text_atoms_room_seq check (room_seq > 0),
    constraint ck_collaborative_text_atoms_revision check (revision > 0),
    constraint ck_collaborative_text_atoms_span_index check (span_index >= 0),
    constraint ck_collaborative_text_atoms_deleted_room_seq check (deleted_at_room_seq is null or deleted_at_room_seq > 0),
    constraint ck_collaborative_text_atoms_content_not_null check (content is not null),
    constraint ck_collaborative_text_atoms_ordering_key_not_blank check (length(ordering_key) > 0)
);

create index idx_collaborative_text_atoms_room_seq
    on collaborative_text_atoms(room_id, room_seq);

create index idx_collaborative_text_atoms_room_anchor
    on collaborative_text_atoms(room_id, anchor_atom_id);

create index idx_collaborative_text_atoms_room_tombstoned
    on collaborative_text_atoms(room_id, tombstoned);

create index idx_collaborative_text_atoms_room_ordering
    on collaborative_text_atoms(room_id, ordering_key);

alter table room_operations
    drop constraint ck_room_operations_operation_type,
    add constraint ck_room_operations_operation_type
        check (operation_type in (
            'TEXT_INSERT', 'TEXT_DELETE', 'TEXT_REPLACE', 'TEXT_INSERT_AFTER', 'TEXT_DELETE_ATOMS', 'NOOP'
        ));
