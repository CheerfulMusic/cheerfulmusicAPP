-- NULL marks pre-migration reservations whose original epoch cannot be proven.
ALTER TABLE agent_request_admission ADD COLUMN conversation_version BIGINT NULL;
