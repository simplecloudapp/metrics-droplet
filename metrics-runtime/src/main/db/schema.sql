-- Create tables if they don't exist
CREATE TABLE IF NOT EXISTS metrics
(
    unique_id    VARCHAR PRIMARY KEY,
    metric_type  VARCHAR  NOT NULL,
    metric_value INTEGER  NOT NULL,
    time         DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS metrics_meta
(
    metric_unique_id VARCHAR NOT NULL,
    data_name        VARCHAR NOT NULL,
    data_value       VARCHAR NOT NULL,
    PRIMARY KEY (metric_unique_id, data_name),
    FOREIGN KEY (metric_unique_id)
    REFERENCES metrics (unique_id)
    ON DELETE CASCADE
    );

-- Add indexes if they don't exist
CREATE INDEX IF NOT EXISTS idx_metrics_type_time
    ON metrics (metric_type, time);

CREATE INDEX IF NOT EXISTS idx_metrics_time
    ON metrics (time);

CREATE INDEX IF NOT EXISTS idx_metrics_meta_name_value
    ON metrics_meta (data_name, data_value);

CREATE INDEX IF NOT EXISTS idx_metrics_meta_value
    ON metrics_meta (data_value);
