create table document_live_states (
    id uuid primary key,
    room_id uuid not null unique references rooms(id),
    document_id uuid not null references documents(id),
    current_room_seq bigint not null default 0,
    current_revision bigint not null default 0,
    content_text text not null default '',
    content_checksum text not null,
    last_operation_id uuid references room_operations(id),
    rebuilt_from_snapshot_id uuid,
    rebuilt_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_document_live_states_room_seq check (current_room_seq >= 0),
    constraint ck_document_live_states_revision check (current_revision >= 0)
);

create table document_state_rebuild_runs (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    document_id uuid not null references documents(id),
    started_at timestamptz not null default now(),
    completed_at timestamptz,
    status text not null,
    from_snapshot_id uuid,
    operations_replayed int not null default 0,
    resulting_revision bigint,
    resulting_room_seq bigint,
    resulting_checksum text,
    error_message text,
    constraint ck_document_state_rebuild_runs_status check (status in ('STARTED', 'COMPLETED', 'FAILED')),
    constraint ck_document_state_rebuild_runs_operations_replayed check (operations_replayed >= 0),
    constraint ck_document_state_rebuild_runs_revision check (resulting_revision is null or resulting_revision >= 0),
    constraint ck_document_state_rebuild_runs_room_seq check (resulting_room_seq is null or resulting_room_seq >= 0)
);

create table room_conflict_resolution_traces (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    operation_attempt_id uuid references room_operation_attempts(id),
    operation_id text not null,
    user_id uuid not null references users(id),
    base_revision bigint not null,
    current_revision bigint not null,
    decision text not null,
    reason text not null,
    incoming_operation_json jsonb not null,
    concurrent_operations_json jsonb not null default '[]'::jsonb,
    transformed_operation_json jsonb,
    created_at timestamptz not null default now(),
    constraint ck_room_conflict_resolution_traces_base_revision check (base_revision >= 0),
    constraint ck_room_conflict_resolution_traces_current_revision check (current_revision >= 0),
    constraint ck_room_conflict_resolution_traces_decision check (
        decision in ('DIRECT_APPLY', 'TRANSFORMED_APPLY', 'NOOP_AFTER_TRANSFORM', 'REJECTED_REQUIRES_RESYNC')
    )
);

create table document_snapshots (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    document_id uuid not null references documents(id),
    room_seq bigint not null,
    revision bigint not null,
    content_text text not null,
    content_checksum text not null,
    snapshot_reason text not null,
    created_at timestamptz not null default now(),
    unique (room_id, revision),
    constraint ck_document_snapshots_room_seq check (room_seq >= 0),
    constraint ck_document_snapshots_revision check (revision >= 0),
    constraint ck_document_snapshots_reason check (snapshot_reason in ('MANUAL', 'PERIODIC', 'REBUILD'))
);

alter table document_live_states
    add constraint fk_document_live_states_rebuilt_snapshot
        foreign key (rebuilt_from_snapshot_id) references document_snapshots(id);

alter table document_state_rebuild_runs
    add constraint fk_document_state_rebuild_runs_snapshot
        foreign key (from_snapshot_id) references document_snapshots(id);

create table room_resume_tokens (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    user_id uuid not null references users(id),
    connection_id text,
    client_session_id text,
    token_hash text unique not null,
    issued_at timestamptz not null default now(),
    expires_at timestamptz not null,
    revoked_at timestamptz,
    last_seen_room_seq bigint not null default 0,
    constraint ck_room_resume_tokens_last_seen check (last_seen_room_seq >= 0),
    constraint ck_room_resume_tokens_expiry check (expires_at > issued_at)
);

create table room_client_offsets (
    room_id uuid not null references rooms(id),
    user_id uuid not null references users(id),
    client_session_id text not null,
    last_seen_room_seq bigint not null,
    updated_at timestamptz not null default now(),
    primary key (room_id, user_id, client_session_id),
    constraint ck_room_client_offsets_last_seen check (last_seen_room_seq >= 0)
);

create table room_backfill_requests (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    user_id uuid not null references users(id),
    client_session_id text,
    from_room_seq bigint not null,
    to_room_seq bigint not null,
    outcome text not null,
    event_count int not null,
    reason text,
    created_at timestamptz not null default now(),
    constraint ck_room_backfill_requests_from_seq check (from_room_seq >= 0),
    constraint ck_room_backfill_requests_to_seq check (to_room_seq >= from_room_seq),
    constraint ck_room_backfill_requests_event_count check (event_count >= 0),
    constraint ck_room_backfill_requests_outcome check (outcome in ('BACKFILLED', 'RESYNC_REQUIRED', 'REJECTED'))
);

create index idx_document_live_states_document_id on document_live_states(document_id);
create index idx_document_live_states_room_revision on document_live_states(room_id, current_revision);

create index idx_document_state_rebuild_runs_room_started on document_state_rebuild_runs(room_id, started_at);
create index idx_document_state_rebuild_runs_status on document_state_rebuild_runs(status);

create index idx_room_conflict_resolution_traces_room_created on room_conflict_resolution_traces(room_id, created_at);
create index idx_room_conflict_resolution_traces_operation on room_conflict_resolution_traces(room_id, operation_id);
create index idx_room_conflict_resolution_traces_decision on room_conflict_resolution_traces(decision);

create index idx_document_snapshots_room_revision on document_snapshots(room_id, revision desc);
create index idx_document_snapshots_room_seq on document_snapshots(room_id, room_seq desc);

create index idx_room_resume_tokens_room_user on room_resume_tokens(room_id, user_id);
create index idx_room_resume_tokens_expires_at on room_resume_tokens(expires_at);
create index idx_room_resume_tokens_client_session on room_resume_tokens(room_id, user_id, client_session_id);

create index idx_room_client_offsets_user on room_client_offsets(user_id, updated_at);

create index idx_room_backfill_requests_room_created on room_backfill_requests(room_id, created_at);
create index idx_room_backfill_requests_user_created on room_backfill_requests(user_id, created_at);
create index idx_room_backfill_requests_outcome on room_backfill_requests(outcome);
