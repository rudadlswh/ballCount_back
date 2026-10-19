CREATE TABLE IF NOT EXISTS attendance_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    installation_id UUID NOT NULL,
    game_id UUID NOT NULL,
    source TEXT NOT NULL DEFAULT 'manual',
    memo TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT attendance_records_unique UNIQUE (installation_id, game_id),
    CONSTRAINT fk_attendance_records_game FOREIGN KEY (game_id) REFERENCES games (id) ON DELETE CASCADE
);

ALTER TABLE attendance_records ENABLE ROW LEVEL SECURITY;

CREATE INDEX IF NOT EXISTS idx_attendance_records_installation_id
    ON attendance_records (installation_id);

CREATE INDEX IF NOT EXISTS idx_attendance_records_game_id
    ON attendance_records (game_id);
