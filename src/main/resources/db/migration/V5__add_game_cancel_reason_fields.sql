ALTER TABLE games
ADD COLUMN cancel_reason VARCHAR(30),
ADD COLUMN raw_cancel_text TEXT;
