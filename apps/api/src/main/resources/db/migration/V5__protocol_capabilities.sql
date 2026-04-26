create table websocket_protocol_sessions (
    connection_id text primary key,
    websocket_session_id text,
    room_id uuid not null references rooms(id),
    user_id uuid not null references users(id),
    client_id text,
    device_id text,
    client_session_id text,
    requested_protocol_version int,
    negotiated_protocol_version int not null,
    server_preferred_protocol_version int not null,
    legacy_default_applied boolean not null default false,
    enabled_capabilities_json jsonb not null default '[]'::jsonb,
    disabled_capabilities_json jsonb not null default '[]'::jsonb,
    rejected_capabilities_json jsonb not null default '[]'::jsonb,
    status text not null,
    rejection_code text,
    rejection_reason text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    closed_at timestamptz,
    constraint chk_websocket_protocol_sessions_status
        check (status in ('NEGOTIATED', 'REJECTED', 'CLOSED'))
);

create index idx_websocket_protocol_sessions_room_user
    on websocket_protocol_sessions(room_id, user_id);

create index idx_websocket_protocol_sessions_room_client
    on websocket_protocol_sessions(room_id, client_id);

create index idx_websocket_protocol_sessions_status
    on websocket_protocol_sessions(status);

create index idx_websocket_protocol_sessions_negotiated_version
    on websocket_protocol_sessions(negotiated_protocol_version);
