create table room_presence_connections (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    user_id uuid not null references users(id),
    connection_id text not null references room_connection_sessions(connection_id),
    websocket_session_id text,
    device_id text,
    client_session_id text,
    status text not null,
    joined_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    expires_at timestamptz not null,
    left_at timestamptz,
    leave_reason text,
    metadata_json jsonb not null default '{}'::jsonb,
    unique (connection_id),
    constraint ck_room_presence_connections_status check (status in ('PRESENT', 'LEFT', 'EXPIRED'))
);

create table room_user_presence (
    room_id uuid not null references rooms(id),
    user_id uuid not null references users(id),
    status text not null,
    active_connection_count int not null,
    active_device_ids jsonb not null default '[]'::jsonb,
    last_seen_at timestamptz not null,
    updated_at timestamptz not null default now(),
    primary key (room_id, user_id),
    constraint ck_room_user_presence_status check (status in ('ONLINE', 'OFFLINE')),
    constraint ck_room_user_presence_active_count check (active_connection_count >= 0)
);

create table room_awareness_states (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    user_id uuid not null references users(id),
    connection_id text not null references room_connection_sessions(connection_id),
    awareness_type text not null,
    cursor_position int,
    anchor_position int,
    focus_position int,
    metadata_json jsonb not null default '{}'::jsonb,
    updated_at timestamptz not null default now(),
    expires_at timestamptz not null,
    status text not null,
    unique (room_id, connection_id, awareness_type),
    constraint ck_room_awareness_states_awareness_type check (awareness_type in ('CURSOR', 'SELECTION')),
    constraint ck_room_awareness_states_status check (status in ('ACTIVE', 'EXPIRED', 'CLEARED')),
    constraint ck_room_awareness_states_cursor_position check (cursor_position is null or cursor_position >= 0),
    constraint ck_room_awareness_states_anchor_position check (anchor_position is null or anchor_position >= 0),
    constraint ck_room_awareness_states_focus_position check (focus_position is null or focus_position >= 0)
);

create table room_sequence_counters (
    room_id uuid primary key references rooms(id),
    current_room_seq bigint not null default 0,
    current_revision bigint not null default 0,
    updated_at timestamptz not null default now(),
    constraint ck_room_sequence_counters_room_seq check (current_room_seq >= 0),
    constraint ck_room_sequence_counters_revision check (current_revision >= 0)
);

create table room_operation_attempts (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    user_id uuid not null references users(id),
    connection_id text,
    operation_id text not null,
    client_seq bigint not null,
    base_revision bigint not null,
    operation_type text not null,
    operation_json jsonb not null,
    outcome text not null,
    nack_code text,
    message text,
    assigned_room_seq bigint,
    resulting_revision bigint,
    duplicate_of_operation_id uuid,
    created_at timestamptz not null default now(),
    constraint ck_room_operation_attempts_client_seq check (client_seq > 0),
    constraint ck_room_operation_attempts_base_revision check (base_revision >= 0),
    constraint ck_room_operation_attempts_outcome check (outcome in ('ACCEPTED', 'DUPLICATE', 'REJECTED'))
);

create table room_operations (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    user_id uuid not null references users(id),
    connection_id text,
    operation_id text not null,
    client_session_id text,
    client_seq bigint not null,
    base_revision bigint not null,
    room_seq bigint not null,
    resulting_revision bigint not null,
    operation_type text not null,
    operation_json jsonb not null,
    created_at timestamptz not null default now(),
    unique (room_id, operation_id),
    unique (room_id, room_seq),
    constraint ck_room_operations_client_seq check (client_seq > 0),
    constraint ck_room_operations_base_revision check (base_revision >= 0),
    constraint ck_room_operations_room_seq check (room_seq > 0),
    constraint ck_room_operations_resulting_revision check (resulting_revision > 0),
    constraint ck_room_operations_operation_type check (operation_type in ('TEXT_INSERT', 'TEXT_DELETE', 'TEXT_REPLACE', 'NOOP'))
);

create unique index ux_room_operations_client_session_seq
    on room_operations(room_id, user_id, client_session_id, client_seq)
    where client_session_id is not null;

create index idx_room_presence_connections_room_status on room_presence_connections(room_id, status);
create index idx_room_presence_connections_user_status on room_presence_connections(user_id, status);
create index idx_room_presence_connections_expires_at on room_presence_connections(expires_at);
create index idx_room_presence_connections_room_user_status on room_presence_connections(room_id, user_id, status);

create index idx_room_user_presence_room_status on room_user_presence(room_id, status);
create index idx_room_user_presence_user_status on room_user_presence(user_id, status);

create index idx_room_awareness_states_room_status on room_awareness_states(room_id, status);
create index idx_room_awareness_states_room_user on room_awareness_states(room_id, user_id);
create index idx_room_awareness_states_expires_at on room_awareness_states(expires_at);

create index idx_room_operation_attempts_room_operation on room_operation_attempts(room_id, operation_id);
create index idx_room_operation_attempts_room_user_client_seq on room_operation_attempts(room_id, user_id, client_seq);
create index idx_room_operation_attempts_room_created on room_operation_attempts(room_id, created_at);
create index idx_room_operation_attempts_outcome on room_operation_attempts(outcome);

create index idx_room_operations_room_seq on room_operations(room_id, room_seq);
create index idx_room_operations_room_revision on room_operations(room_id, resulting_revision);
create index idx_room_operations_room_user on room_operations(room_id, user_id);
create index idx_room_operations_room_created on room_operations(room_id, created_at);
