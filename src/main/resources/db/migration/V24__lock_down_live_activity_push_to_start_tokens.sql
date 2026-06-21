ALTER TABLE kbo_crawler_api.live_activity_push_to_start_tokens ENABLE ROW LEVEL SECURITY;

REVOKE ALL PRIVILEGES ON TABLE kbo_crawler_api.live_activity_push_to_start_tokens FROM anon, authenticated;
