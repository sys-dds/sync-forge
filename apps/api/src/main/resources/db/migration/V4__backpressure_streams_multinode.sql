create table websocket_connection_flow_controls (
    connection_id text primary key,
    room_id uuid not null references rooms(id),
    user_id uuid not null references users(id),
    websocket_session_id text,
    node_id text not null,
    status text not null,
    queued_messages int not null default 0,
    max_queued_messages int not null,
    last_send_started_at timestamptz,
    last_send_completed_at timestamptz,
    last_send_error text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_websocket_connection_flow_controls_status
        check (status in ('ACTIVE', 'SLOW', 'QUARANTINED', 'CLOSED')),
    constraint chk_websocket_connection_flow_controls_queue
        check (queued_messages >= 0 and max_queued_messages > 0)
);

create table room_rate_limit_events (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    user_id uuid not null references users(id),
    connection_id text,
    client_session_id text,
    operation_id text,
    limit_key text not null,
    limit_value int not null,
    observed_value int not null,
    window_seconds int not null,
    decision text not null,
    reason text not null,
    created_at timestamptz not null default now(),
    constraint chk_room_rate_limit_events_decision
        check (decision in ('ALLOWED', 'REJECTED')),
    constraint chk_room_rate_limit_events_values
        check (limit_value > 0 and observed_value >= 0 and window_seconds > 0)
);

create table room_backpressure_states (
    room_id uuid primary key references rooms(id),
    status text not null,
    pending_events int not null default 0,
    max_pending_events int not null,
    last_triggered_at timestamptz,
    last_cleared_at timestamptz,
    reason text,
    updated_at timestamptz not null default now(),
    constraint chk_room_backpressure_states_status
        check (status in ('NORMAL', 'WARNING', 'REJECTING')),
    constraint chk_room_backpressure_states_pending
        check (pending_events >= 0 and max_pending_events > 0)
);

create table websocket_slow_consumer_events (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    user_id uuid not null references users(id),
    connection_id text not null,
    node_id text not null,
    queued_messages int not null,
    threshold int not null,
    decision text not null,
    reason text not null,
    created_at timestamptz not null default now(),
    constraint chk_websocket_slow_consumer_events_decision
        check (decision in ('WARNED', 'QUARANTINED', 'DISCONNECTED')),
    constraint chk_websocket_slow_consumer_events_values
        check (queued_messages >= 0 and threshold > 0)
);

create table websocket_session_quarantines (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    user_id uuid not null references users(id),
    connection_id text not null,
    client_session_id text,
    node_id text not null,
    reason text not null,
    started_at timestamptz not null default now(),
    expires_at timestamptz not null,
    released_at timestamptz,
    constraint chk_websocket_session_quarantines_expiry
        check (expires_at > started_at)
);

create table room_stream_offsets (
    room_id uuid not null references rooms(id),
    node_id text not null,
    stream_key text not null,
    last_stream_id text,
    last_room_seq bigint not null default 0,
    updated_at timestamptz not null default now(),
    primary key (room_id, node_id),
    constraint chk_room_stream_offsets_seq
        check (last_room_seq >= 0)
);

create table syncforge_node_heartbeats (
    node_id text primary key,
    started_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    status text not null,
    metadata_json jsonb not null default '{}'::jsonb,
    constraint chk_syncforge_node_heartbeats_status
        check (status in ('ACTIVE', 'STALE', 'STOPPED'))
);

create table node_room_subscriptions (
    node_id text not null,
    room_id uuid not null references rooms(id),
    local_connection_count int not null default 0,
    subscribed_at timestamptz not null default now(),
    last_event_at timestamptz,
    primary key (node_id, room_id),
    constraint chk_node_room_subscriptions_count
        check (local_connection_count >= 0)
);

create table cross_node_presence_states (
    room_id uuid not null references rooms(id),
    user_id uuid not null references users(id),
    node_id text not null,
    active_connection_count int not null default 0,
    last_seen_at timestamptz not null default now(),
    status text not null,
    primary key (room_id, user_id, node_id),
    constraint chk_cross_node_presence_states_status
        check (status in ('ONLINE', 'OFFLINE', 'STALE')),
    constraint chk_cross_node_presence_states_count
        check (active_connection_count >= 0)
);

create index idx_room_rate_limit_events_room_user_created
    on room_rate_limit_events(room_id, user_id, created_at);
create index idx_room_rate_limit_events_limit_key_created
    on room_rate_limit_events(limit_key, created_at);
create index idx_websocket_slow_consumer_events_room_created
    on websocket_slow_consumer_events(room_id, created_at);
create index idx_websocket_session_quarantines_room_user_expires
    on websocket_session_quarantines(room_id, user_id, expires_at);
create index idx_room_backpressure_states_status
    on room_backpressure_states(status);
create index idx_syncforge_node_heartbeats_status_seen
    on syncforge_node_heartbeats(status, last_seen_at);
create index idx_node_room_subscriptions_room
    on node_room_subscriptions(room_id);
create index idx_cross_node_presence_states_room_user_status
    on cross_node_presence_states(room_id, user_id, status);
