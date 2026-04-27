create table room_ownership_leases (
    room_id uuid primary key references rooms(id),
    owner_node_id text not null,
    fencing_token bigint not null,
    lease_status text not null,
    lease_expires_at timestamptz not null,
    acquired_at timestamptz not null default now(),
    renewed_at timestamptz,
    released_at timestamptz,
    last_takeover_reason text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_room_ownership_leases_token check (fencing_token > 0),
    constraint ck_room_ownership_leases_status check (lease_status in ('ACTIVE', 'EXPIRED', 'RELEASED'))
);

create index idx_room_ownership_leases_owner_node
    on room_ownership_leases(owner_node_id);

create index idx_room_ownership_leases_status_expiry
    on room_ownership_leases(lease_status, lease_expires_at);

create index idx_room_ownership_leases_expiry
    on room_ownership_leases(lease_expires_at);

create table room_ownership_events (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    node_id text not null,
    fencing_token bigint,
    event_type text not null,
    reason text,
    previous_owner_node_id text,
    previous_fencing_token bigint,
    created_at timestamptz not null default now(),
    constraint ck_room_ownership_events_type check (event_type in (
        'ACQUIRED',
        'RENEWED',
        'RELEASED',
        'EXPIRED',
        'TAKEOVER',
        'STALE_OWNER_REJECTED',
        'FENCED_WRITE_REJECTED',
        'FENCED_PUBLISH_REJECTED'
    ))
);

create index idx_room_ownership_events_room_created
    on room_ownership_events(room_id, created_at);

create index idx_room_ownership_events_node_created
    on room_ownership_events(node_id, created_at);

create index idx_room_ownership_events_type_created
    on room_ownership_events(event_type, created_at);

alter table room_operations
    add column owner_node_id text,
    add column fencing_token bigint,
    add constraint ck_room_operations_fencing_token check (fencing_token is null or fencing_token > 0);

alter table room_event_outbox
    add column owner_node_id text,
    add column fencing_token bigint,
    add constraint ck_room_event_outbox_fencing_token check (fencing_token is null or fencing_token > 0);

create index idx_room_operations_owner_fencing
    on room_operations(room_id, owner_node_id, fencing_token);

create index idx_room_event_outbox_owner_fencing
    on room_event_outbox(room_id, owner_node_id, fencing_token);
