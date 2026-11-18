-- MySQL 8.0. Run on the isolated benchmark instance only.
-- Capture before/after; compute DELTA, never treat lifetime totals as test results.
SHOW GLOBAL STATUS WHERE Variable_name IN
 ('Innodb_row_lock_time','Innodb_row_lock_waits','Innodb_row_lock_current_waits',
  'Innodb_row_lock_time_max','Innodb_deadlocks','Uptime');
-- row_lock_time is milliseconds. Mean wait = delta(time)/delta(waits).
-- This is global, so isolate other workloads. Do not FLUSH STATUS during a run.

-- Current waiting/blocking transactions, SQL and wait duration (MySQL sys schema).
SELECT waiting_pid, blocking_pid, wait_age_secs, locked_table_schema,
       locked_table_name, locked_index, waiting_query, blocking_query
FROM sys.innodb_lock_waits
ORDER BY wait_age_secs DESC;

-- Raw wait edges: available even if sys view is not installed.
SELECT w.REQUESTING_ENGINE_TRANSACTION_ID AS waiting_trx,
       w.BLOCKING_ENGINE_TRANSACTION_ID AS blocking_trx,
       r.OBJECT_SCHEMA, r.OBJECT_NAME, r.INDEX_NAME,
       r.LOCK_TYPE, r.LOCK_MODE, r.LOCK_DATA
FROM performance_schema.data_lock_waits w
JOIN performance_schema.data_locks r
 ON r.ENGINE=w.ENGINE AND r.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID;

SELECT trx_id, trx_state, trx_started, trx_wait_started,
       trx_mysql_thread_id, trx_query
FROM information_schema.innodb_trx
ORDER BY trx_started;

-- Only the MOST RECENT deadlock; not sufficient by itself to prove no deadlocks.
SHOW ENGINE INNODB STATUS;
-- DBA may enable innodb_print_all_deadlocks on the isolated instance, collect error log,
-- and restore the previous setting after the benchmark. Never change production here.

-- Investigate durable callback retries/errors.
SELECT provider, merchant_scope, env_scope, event_id, event_type,
       status, attempts, last_error, next_attempt_at, lease_until
FROM payment_webhook_inbox
WHERE env_scope='sandbox'
ORDER BY updated_at DESC LIMIT 200;
