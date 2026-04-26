create table room_event_outbox (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    room_seq bigint not null,
    revision bigint not null,
    operation_id text not null,
    event_type text not null,
    logical_event_key text not null,
    payload_json jsonb not null,
    status text not null,
    attempt_count int not null default 0,
    max_attempts int not null default 10,
    next_attempt_at timestamptz not null default now(),
    locked_by text,
    locked_until timestamptz,
    last_error text,
    published_stream_key text,
    published_stream_id text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    published_at timestamptz,
    parked_at timestamptz,
    constraint uq_room_event_outbox_room_seq unique (room_id, room_seq),
    constraint uq_room_event_outbox_logical_event_key unique (logical_event_key),
    constraint chk_room_event_outbox_attempt_count check (attempt_count >= 0),
    constraint chk_room_event_outbox_max_attempts check (max_attempts > 0),
    constraint chk_room_event_outbox_status check (status in ('PENDING', 'PUBLISHING', 'RETRY', 'PUBLISHED', 'PARKED'))
);

create index idx_room_event_outbox_status_next_attempt
    on room_event_outbox(status, next_attempt_at);

create index idx_room_event_outbox_room_seq
    on room_event_outbox(room_id, room_seq);

create index idx_room_event_outbox_operation_id
    on room_event_outbox(operation_id);

create index idx_room_event_outbox_locked_until
    on room_event_outbox(locked_until);

create index idx_room_event_outbox_created_at
    on room_event_outbox(created_at);

alter table room_stream_offsets
    add column status text not null default 'NORMAL',
    add column expected_room_seq bigint,
    add column observed_room_seq bigint,
    add column last_gap_at timestamptz,
    add column last_error text,
    add constraint chk_room_stream_offsets_status check (status in ('NORMAL', 'GAP_DETECTED'));
