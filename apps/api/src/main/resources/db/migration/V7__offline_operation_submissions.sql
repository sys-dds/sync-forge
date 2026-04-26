create table offline_operation_submissions (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    user_id uuid not null references users(id),
    client_id text not null,
    client_operation_id text not null,
    base_room_seq bigint not null,
    base_revision bigint not null,
    canonical_payload_hash text not null,
    causal_dependencies_json jsonb not null default '[]'::jsonb,
    status text not null,
    accepted_operation_id text,
    accepted_room_seq bigint,
    rejection_code text,
    rejection_reason text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (room_id, user_id, client_operation_id),
    constraint ck_offline_operation_submissions_status check (status in ('ACCEPTED', 'REJECTED')),
    constraint ck_offline_operation_submissions_base_room_seq check (base_room_seq >= 0),
    constraint ck_offline_operation_submissions_base_revision check (base_revision >= 0),
    constraint ck_offline_operation_submissions_accepted_room_seq check (accepted_room_seq is null or accepted_room_seq > 0)
);

create index idx_offline_operation_submissions_room_client_operation
    on offline_operation_submissions(room_id, client_operation_id);

create index idx_offline_operation_submissions_room_user
    on offline_operation_submissions(room_id, user_id);

create index idx_offline_operation_submissions_status
    on offline_operation_submissions(status);

create index idx_offline_operation_submissions_created
    on offline_operation_submissions(created_at);
