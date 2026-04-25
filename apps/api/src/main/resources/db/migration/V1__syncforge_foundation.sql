create table workspaces (
    id uuid primary key,
    workspace_key text unique not null,
    name text not null,
    status text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_workspaces_status check (status in ('ACTIVE', 'DISABLED'))
);

create table users (
    id uuid primary key,
    external_user_key text unique not null,
    display_name text not null,
    status text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_users_status check (status in ('ACTIVE', 'DISABLED'))
);

create table documents (
    id uuid primary key,
    workspace_id uuid not null references workspaces(id),
    document_key text not null,
    title text not null,
    status text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (workspace_id, document_key),
    constraint ck_documents_status check (status in ('ACTIVE', 'ARCHIVED'))
);

create table rooms (
    id uuid primary key,
    workspace_id uuid not null references workspaces(id),
    document_id uuid not null references documents(id),
    room_key text not null,
    room_type text not null,
    status text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (workspace_id, room_key),
    constraint ck_rooms_room_type check (room_type in ('DOCUMENT')),
    constraint ck_rooms_status check (status in ('OPEN', 'CLOSED'))
);

create table room_memberships (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    user_id uuid not null references users(id),
    role text not null,
    status text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (room_id, user_id),
    constraint ck_room_memberships_role check (role in ('OWNER', 'EDITOR', 'VIEWER')),
    constraint ck_room_memberships_status check (status in ('ACTIVE', 'REMOVED'))
);

create table room_connection_sessions (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    user_id uuid not null references users(id),
    connection_id text not null unique,
    websocket_session_id text,
    device_id text,
    client_session_id text,
    status text not null,
    connected_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    disconnected_at timestamptz,
    disconnect_reason text,
    constraint ck_room_connection_sessions_status check (status in ('CONNECTED', 'DISCONNECTED'))
);

create table room_connection_events (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    user_id uuid not null references users(id),
    connection_id text not null,
    event_type text not null,
    event_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint ck_room_connection_events_event_type check (
        event_type in ('CONNECTED', 'JOINED_ROOM', 'LEFT_ROOM', 'PING', 'DISCONNECTED', 'ERROR')
    )
);

create index idx_documents_workspace_id on documents(workspace_id);
create index idx_rooms_workspace_id on rooms(workspace_id);
create index idx_rooms_document_id on rooms(document_id);
create index idx_room_memberships_room_user on room_memberships(room_id, user_id);
create index idx_room_memberships_user on room_memberships(user_id);
create index idx_room_connection_sessions_room_status on room_connection_sessions(room_id, status);
create index idx_room_connection_sessions_user_status on room_connection_sessions(user_id, status);
create index idx_room_connection_sessions_connection_id on room_connection_sessions(connection_id);
create index idx_room_connection_events_room_created on room_connection_events(room_id, created_at);
create index idx_room_connection_events_connection_id on room_connection_events(connection_id);
