CREATE TABLE leaderboard_profiles (
 user_id UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
 nickname VARCHAR(24) NOT NULL,
 stars INTEGER NOT NULL DEFAULT 0 CHECK(stars BETWEEN 0 AND 3000),
 completed INTEGER NOT NULL DEFAULT 0 CHECK(completed BETWEEN 0 AND 1000),
 joined_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX leaderboard_score_order ON leaderboard_profiles(stars DESC,completed DESC,user_id);
