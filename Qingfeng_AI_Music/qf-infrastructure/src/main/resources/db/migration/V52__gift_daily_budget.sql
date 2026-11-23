CREATE TABLE gift_daily_budget (
    gift_kind VARCHAR(16) NOT NULL,
    usage_day DATE NOT NULL,
    spent BIGINT NOT NULL,
    PRIMARY KEY (gift_kind, usage_day)
);
