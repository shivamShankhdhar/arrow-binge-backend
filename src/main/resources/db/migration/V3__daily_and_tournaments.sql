CREATE TABLE event_runs (
 id UUID PRIMARY KEY,
 user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 mode VARCHAR(16) NOT NULL CHECK(mode IN ('daily','tournament')),
 event_key VARCHAR(32) NOT NULL,
 level_id INT NOT NULL,
 started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 expires_at TIMESTAMPTZ NOT NULL,
 finished_at TIMESTAMPTZ,
 points INT NOT NULL DEFAULT 0
);
CREATE INDEX event_runs_user ON event_runs(user_id,mode,event_key);
CREATE TABLE daily_completions (
 user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 day DATE NOT NULL,
 level_id INT NOT NULL,
 completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(user_id,day)
);
CREATE TABLE tournament_players (
 user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 week DATE NOT NULL,
 nickname VARCHAR(24) NOT NULL,
 points INT NOT NULL DEFAULT 0 CHECK(points>=0),
 completed INT NOT NULL DEFAULT 0 CHECK(completed>=0),
 PRIMARY KEY(user_id,week)
);
CREATE INDEX tournament_score_order ON tournament_players(week,points DESC,completed DESC,user_id);
CREATE TABLE tournament_completions (
 user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 week DATE NOT NULL,
 level_id INT NOT NULL,
 points INT NOT NULL,
 PRIMARY KEY(user_id,week,level_id)
);
