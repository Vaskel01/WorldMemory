CREATE TABLE IF NOT EXISTS players (
    player_uuid UUID PRIMARY KEY,
    current_name TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen TIMESTAMPTZ NOT NULL DEFAULT now(),
    data_version INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS player_profiles (
    player_uuid UUID PRIMARY KEY REFERENCES players(player_uuid) ON DELETE CASCADE,
    route_id TEXT NOT NULL DEFAULT 'route_a',
    chapter INTEGER NOT NULL DEFAULT 1,
    checkpoint_id TEXT,
    active_zone_id TEXT,
    profile_version BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS player_settings (
    player_uuid UUID NOT NULL REFERENCES players(player_uuid) ON DELETE CASCADE,
    key TEXT NOT NULL,
    value_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (player_uuid, key)
);

CREATE TABLE IF NOT EXISTS player_unlocks (
    player_uuid UUID NOT NULL REFERENCES players(player_uuid) ON DELETE CASCADE,
    unlock_id TEXT NOT NULL,
    source TEXT NOT NULL,
    unlocked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (player_uuid, unlock_id)
);

CREATE TABLE IF NOT EXISTS instances (
    instance_id UUID PRIMARY KEY,
    owner_uuid UUID NOT NULL REFERENCES players(player_uuid) ON DELETE CASCADE,
    template_id TEXT NOT NULL,
    template_version INTEGER NOT NULL,
    state TEXT NOT NULL,
    world_name TEXT NOT NULL UNIQUE,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    source_server TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_instances_active_owner
    ON instances(owner_uuid) WHERE state NOT IN ('ARCHIVED', 'FAILED');

CREATE TABLE IF NOT EXISTS instance_memberships (
    instance_id UUID NOT NULL REFERENCES instances(instance_id) ON DELETE CASCADE,
    player_uuid UUID NOT NULL REFERENCES players(player_uuid) ON DELETE CASCADE,
    role TEXT NOT NULL,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at TIMESTAMPTZ,
    PRIMARY KEY (instance_id, player_uuid, joined_at)
);

CREATE TABLE IF NOT EXISTS checkpoints (
    player_uuid UUID NOT NULL REFERENCES players(player_uuid) ON DELETE CASCADE,
    checkpoint_id TEXT NOT NULL,
    instance_id UUID REFERENCES instances(instance_id) ON DELETE SET NULL,
    route TEXT NOT NULL,
    chapter INTEGER NOT NULL,
    activated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (player_uuid, checkpoint_id, activated_at)
);

CREATE TABLE IF NOT EXISTS world_mutations (
    instance_id UUID NOT NULL REFERENCES instances(instance_id) ON DELETE CASCADE,
    mutation_id TEXT NOT NULL,
    state TEXT NOT NULL,
    payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (instance_id, mutation_id)
);

CREATE TABLE IF NOT EXISTS zone_states (
    instance_id UUID NOT NULL REFERENCES instances(instance_id) ON DELETE CASCADE,
    zone_id TEXT NOT NULL,
    state_id TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (instance_id, zone_id)
);

CREATE TABLE IF NOT EXISTS zone_connections (
    instance_id UUID NOT NULL REFERENCES instances(instance_id) ON DELETE CASCADE,
    connection_id TEXT NOT NULL,
    state TEXT NOT NULL,
    source TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (instance_id, connection_id)
);

CREATE TABLE IF NOT EXISTS ash_wallets (
    player_uuid UUID PRIMARY KEY REFERENCES players(player_uuid) ON DELETE CASCADE,
    current_ash INTEGER NOT NULL DEFAULT 0 CHECK (current_ash >= 0),
    capacity INTEGER NOT NULL DEFAULT 100 CHECK (capacity >= 0),
    saturation INTEGER NOT NULL DEFAULT 0 CHECK (saturation >= 0),
    reserved_ash INTEGER NOT NULL DEFAULT 0 CHECK (reserved_ash >= 0),
    version BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ash_transactions (
    transaction_id UUID PRIMARY KEY,
    player_uuid UUID NOT NULL REFERENCES players(player_uuid) ON DELETE CASCADE,
    actor_uuid UUID,
    effective_host_uuid UUID,
    instance_id UUID REFERENCES instances(instance_id) ON DELETE SET NULL,
    amount INTEGER NOT NULL,
    reason TEXT NOT NULL,
    source_id TEXT,
    idempotency_key TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS anchor_states (
    instance_id UUID NOT NULL REFERENCES instances(instance_id) ON DELETE CASCADE,
    anchor_id TEXT NOT NULL,
    state TEXT NOT NULL,
    activation_id UUID,
    expires_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (instance_id, anchor_id)
);

CREATE TABLE IF NOT EXISTS remnants (
    remnant_id UUID PRIMARY KEY,
    owner_uuid UUID NOT NULL REFERENCES players(player_uuid) ON DELETE CASCADE,
    source_instance_id UUID REFERENCES instances(instance_id) ON DELETE SET NULL,
    template_id TEXT NOT NULL,
    template_version INTEGER NOT NULL,
    route TEXT NOT NULL,
    chapter INTEGER NOT NULL,
    zone_id TEXT,
    world_x DOUBLE PRECISION NOT NULL,
    world_y DOUBLE PRECISION NOT NULL,
    world_z DOUBLE PRECISION NOT NULL,
    yaw REAL NOT NULL DEFAULT 0,
    pitch REAL NOT NULL DEFAULT 0,
    ash INTEGER NOT NULL DEFAULT 0,
    message_key TEXT,
    status TEXT NOT NULL,
    death_cause TEXT,
    clarity INTEGER NOT NULL DEFAULT 100,
    privacy_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_remnants_personal_owner
    ON remnants(owner_uuid) WHERE status = 'PERSONAL';
CREATE INDEX IF NOT EXISTS idx_remnants_compat
    ON remnants(status, template_id, template_version, route, zone_id, created_at);

CREATE TABLE IF NOT EXISTS remnant_interactions (
    interaction_id UUID PRIMARY KEY,
    remnant_id UUID NOT NULL REFERENCES remnants(remnant_id) ON DELETE CASCADE,
    viewer_uuid UUID NOT NULL REFERENCES players(player_uuid) ON DELETE CASCADE,
    effective_host_uuid UUID,
    type TEXT NOT NULL,
    context_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS phantom_recordings (
    recording_id UUID PRIMARY KEY,
    remnant_id UUID NOT NULL UNIQUE REFERENCES remnants(remnant_id) ON DELETE CASCADE,
    format_version INTEGER NOT NULL,
    duration_ms INTEGER NOT NULL,
    frame_count INTEGER NOT NULL,
    compressed_data BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS coop_sessions (
    session_id UUID PRIMARY KEY,
    host_uuid UUID NOT NULL REFERENCES players(player_uuid) ON DELETE CASCADE,
    instance_id UUID REFERENCES instances(instance_id) ON DELETE SET NULL,
    state TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS coop_members (
    session_id UUID NOT NULL REFERENCES coop_sessions(session_id) ON DELETE CASCADE,
    player_uuid UUID NOT NULL REFERENCES players(player_uuid) ON DELETE CASCADE,
    role TEXT NOT NULL,
    return_location_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    reward_snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    state TEXT NOT NULL,
    PRIMARY KEY (session_id, player_uuid)
);

CREATE TABLE IF NOT EXISTS coop_rewards (
    reward_id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES coop_sessions(session_id) ON DELETE CASCADE,
    player_uuid UUID NOT NULL REFERENCES players(player_uuid) ON DELETE CASCADE,
    reward_type TEXT NOT NULL,
    payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    claimed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS quests (
    owner_uuid UUID NOT NULL REFERENCES players(player_uuid) ON DELETE CASCADE,
    quest_id TEXT NOT NULL,
    stage_id TEXT,
    state TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_uuid, quest_id)
);

CREATE TABLE IF NOT EXISTS quest_objectives (
    owner_uuid UUID NOT NULL REFERENCES players(player_uuid) ON DELETE CASCADE,
    quest_id TEXT NOT NULL,
    objective_id TEXT NOT NULL,
    progress_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    completed_at TIMESTAMPTZ,
    completion_key TEXT,
    PRIMARY KEY (owner_uuid, quest_id, objective_id),
    UNIQUE (completion_key)
);

CREATE TABLE IF NOT EXISTS npc_states (
    instance_id UUID NOT NULL REFERENCES instances(instance_id) ON DELETE CASCADE,
    npc_key TEXT NOT NULL,
    state_id TEXT NOT NULL,
    location_id TEXT,
    payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (instance_id, npc_key)
);

CREATE TABLE IF NOT EXISTS npc_relationships (
    owner_uuid UUID NOT NULL REFERENCES players(player_uuid) ON DELETE CASCADE,
    npc_key TEXT NOT NULL,
    trust INTEGER NOT NULL DEFAULT 0,
    fear INTEGER NOT NULL DEFAULT 0,
    dependence INTEGER NOT NULL DEFAULT 0,
    resentment INTEGER NOT NULL DEFAULT 0,
    honesty INTEGER NOT NULL DEFAULT 0,
    control INTEGER NOT NULL DEFAULT 0,
    facts_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (owner_uuid, npc_key)
);

CREATE TABLE IF NOT EXISTS faction_states (
    owner_uuid UUID NOT NULL REFERENCES players(player_uuid) ON DELETE CASCADE,
    faction_id TEXT NOT NULL,
    state_id TEXT NOT NULL DEFAULT 'NEUTRAL',
    facts_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 1,
    PRIMARY KEY (owner_uuid, faction_id)
);

CREATE TABLE IF NOT EXISTS memory_actions (
    action_id UUID PRIMARY KEY,
    actor_uuid UUID NOT NULL,
    effective_host_uuid UUID NOT NULL,
    instance_id UUID REFERENCES instances(instance_id) ON DELETE SET NULL,
    action_type TEXT NOT NULL,
    target_id TEXT,
    context_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_memory_actions_host_type_time
    ON memory_actions(effective_host_uuid, action_type, created_at);
CREATE INDEX IF NOT EXISTS idx_memory_actions_target
    ON memory_actions(target_id);

CREATE TABLE IF NOT EXISTS memory_scores (
    owner_uuid UUID NOT NULL REFERENCES players(player_uuid) ON DELETE CASCADE,
    score_id TEXT NOT NULL,
    value DOUBLE PRECISION NOT NULL DEFAULT 0,
    confidence DOUBLE PRECISION NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_uuid, score_id)
);

CREATE TABLE IF NOT EXISTS memory_flags (
    owner_uuid UUID NOT NULL REFERENCES players(player_uuid) ON DELETE CASCADE,
    flag_id TEXT NOT NULL,
    source_action_id UUID,
    set_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_uuid, flag_id)
);

CREATE TABLE IF NOT EXISTS reaction_history (
    owner_uuid UUID NOT NULL REFERENCES players(player_uuid) ON DELETE CASCADE,
    reaction_id TEXT NOT NULL,
    trigger_count INTEGER NOT NULL DEFAULT 0,
    last_triggered TIMESTAMPTZ,
    payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (owner_uuid, reaction_id)
);

CREATE TABLE IF NOT EXISTS encounter_sessions (
    encounter_session_id UUID PRIMARY KEY,
    instance_id UUID NOT NULL REFERENCES instances(instance_id) ON DELETE CASCADE,
    encounter_id TEXT NOT NULL,
    state TEXT NOT NULL,
    participants_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS boss_outcomes (
    instance_id UUID NOT NULL REFERENCES instances(instance_id) ON DELETE CASCADE,
    boss_id TEXT NOT NULL,
    outcome_id TEXT NOT NULL,
    participants_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (instance_id, boss_id)
);

CREATE TABLE IF NOT EXISTS custom_items (
    item_instance_id UUID PRIMARY KEY,
    owner_uuid UUID REFERENCES players(player_uuid) ON DELETE SET NULL,
    definition_id TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    state_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS collective_metrics (
    season_id TEXT NOT NULL,
    metric_id TEXT NOT NULL,
    bucket TEXT NOT NULL,
    value BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (season_id, metric_id, bucket)
);

CREATE TABLE IF NOT EXISTS admin_audit (
    audit_id UUID PRIMARY KEY,
    actor_uuid UUID,
    action_type TEXT NOT NULL,
    target TEXT,
    payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
