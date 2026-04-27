create table room_runtime_controls (
    room_id uuid primary key references rooms(id),
    writes_paused boolean not null default false,
    force_resync_generation bigint not null default 0,
    force_resync_reason text,
    repair_required boolean not null default false,
    last_control_action text,
    last_control_reason text,
    last_control_actor uuid,
    updated_at timestamptz not null default now(),
    constraint ck_room_runtime_controls_resync_generation check (force_resync_generation >= 0),
    constraint ck_room_runtime_controls_action check (
        last_control_action is null or last_control_action in (
            'PAUSE_WRITES',
            'RESUME_WRITES',
            'FORCE_RESYNC',
            'MARK_REPAIR_REQUIRED',
            'CLEAR_REPAIR_REQUIRED',
            'REBUILD_STATE'
        )
    )
);

create table room_runtime_control_events (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    actor_user_id uuid not null references users(id),
    action text not null,
    reason text not null,
    previous_state_json jsonb not null default '{}'::jsonb,
    new_state_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint ck_room_runtime_control_events_action check (action in (
        'PAUSE_WRITES',
        'RESUME_WRITES',
        'FORCE_RESYNC',
        'MARK_REPAIR_REQUIRED',
        'CLEAR_REPAIR_REQUIRED',
        'REBUILD_STATE'
    ))
);

create index idx_room_runtime_control_events_room_created
    on room_runtime_control_events(room_id, created_at desc);

create table room_poison_operations (
    id uuid primary key,
    room_id uuid not null references rooms(id),
    operation_id text not null,
    room_seq bigint,
    reason text not null,
    failure_count int not null default 1,
    first_seen_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now(),
    status text not null,
    constraint ck_room_poison_operations_failure_count check (failure_count > 0),
    constraint ck_room_poison_operations_room_seq check (room_seq is null or room_seq >= 0),
    constraint ck_room_poison_operations_status check (status in ('QUARANTINED', 'CLEARED')),
    constraint uq_room_poison_operations_room_operation unique (room_id, operation_id)
);

create index idx_room_poison_operations_room_status
    on room_poison_operations(room_id, status);
