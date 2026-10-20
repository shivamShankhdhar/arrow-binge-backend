\set ON_ERROR_STOP on
BEGIN;
INSERT INTO users(id,google_subject,display_name)
SELECT md5('audit-player-'||i)::uuid,'audit-player-'||i,'Audit player' FROM generate_series(1,100000) i;
INSERT INTO leaderboard_profiles(user_id,nickname,stars,completed)
SELECT md5('audit-player-'||i)::uuid,'Audit player', i%3001,i%1001 FROM generate_series(1,100000) i;
INSERT INTO level_attempts(user_id,level_id,device_id,run_id,content_version,checkpoint,state,closed,updated_at)
SELECT md5('audit-player-1')::uuid,1,md5('audit-device')::uuid,md5('audit-run-'||i)::uuid,'discovery-v3',i,'{}',i%100<>0,CURRENT_TIMESTAMP-i*interval '1 second' FROM generate_series(1,100000) i;
ANALYZE leaderboard_profiles;
ANALYZE level_attempts;
\echo TOP_50
EXPLAIN (ANALYZE,BUFFERS) SELECT * FROM leaderboard_profiles ORDER BY stars DESC,completed DESC,user_id LIMIT 50;
\echo OWN_RANK_LOW_SCORE
EXPLAIN (ANALYZE,BUFFERS) SELECT COUNT(*) FROM leaderboard_profiles WHERE (stars,completed)>(1,1);
\echo PARTICIPANTS
EXPLAIN (ANALYZE,BUFFERS) SELECT COUNT(*) FROM leaderboard_profiles;
\echo OPEN_ATTEMPTS_BEFORE
EXPLAIN (ANALYZE,BUFFERS) SELECT state::text,device_id FROM level_attempts WHERE user_id=md5('audit-player-1')::uuid AND closed=false ORDER BY updated_at DESC;
CREATE INDEX audit_open_attempts ON level_attempts(user_id,updated_at DESC) WHERE closed=false;
\echo OPEN_ATTEMPTS_AFTER
EXPLAIN (ANALYZE,BUFFERS) SELECT state::text,device_id FROM level_attempts WHERE user_id=md5('audit-player-1')::uuid AND closed=false ORDER BY updated_at DESC;
ROLLBACK;
