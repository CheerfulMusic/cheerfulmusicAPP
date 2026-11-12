# Agent retrieval

The feature is disabled by default. Applying V36 adds a history-reset epoch and a durable index-job table; it neither contacts providers nor indexes historical works. Existing Flyway migrations are unchanged.

## Configuration

Operator configuration, outside Git:

| Property | Purpose |
| --- | --- |
| `qingfeng.agent.retrieval.enabled` | Enable indexing events and semantic search; default `false` |
| `qingfeng.agent.retrieval.scheduler-enabled` | Run the index worker; default `false`, enable only on the worker role |
| `qingfeng.agent.retrieval.embedding-url` | Explicit OpenAI-compatible embeddings endpoint; no default |
| `qingfeng.agent.retrieval.embedding-model` | Dedicated embedding model name; no default |
| `qingfeng.agent.retrieval.embedding-api-key` | Optional provider secret |
| `qingfeng.agent.retrieval.dimensions` | Expected vector dimension; must match the model and collection |
| `qingfeng.agent.retrieval.qdrant-url` | Explicit Qdrant base URL; no default |
| `qingfeng.agent.retrieval.collection` | Pre-created collection name with cosine vectors matching the configured dimension |
| `qingfeng.agent.retrieval.qdrant-api-key` | Optional Qdrant secret |
| `qingfeng.agent.retrieval.poll-delay-ms` | Worker polling fallback, default 3000 ms |

HTTPS is required except for loopback development services. HTTP redirects are disabled. The application does not create or migrate a Qdrant collection. Changing the embedding model requires a new compatible collection and an explicitly authorized rebuild, not automatic reuse of unrelated vectors.

## Scope and permission boundary

The private corpus contains a work's title, story, message, and latest confirmed lyrics for its current work version. Embedding input is bounded to the first 8000 characters. Draft lyrics, chat text, account recovery, credentials, credits and orders are excluded. There is no community/public index in this release. Sharing and revoking a share do not alter the private owner corpus or grant another account retrieval access.

Only changes made after enabling the feature are queued. An explicit “把当前作品加入语义检索” queues that single older work; no automatic historical backfill runs. `search_memory` returns a clear unavailable response and a keyword-search command when not configured or when a provider fails. Missing/stale vectors are never described as a complete library search.

Qdrant payloads contain owner ID, PRIVATE visibility, work/revision version, content hash and schema, not source text. Search filters owner/visibility/schema/deleted status in Qdrant and then checks owner, soft deletion, current version, revision and complete source hash against MySQL. Displayed excerpts come from the authoritative current record. Thus stale or forged vectors, deleted works and foreign-owner results are discarded even before the asynchronous delete finishes. Retrieved excerpts are labelled untrusted source data and never become commands.

Point IDs include owner, work, full source hash and embedding schema. A delayed old writer therefore cannot replace a newer vector even if it crashes immediately after its HTTP write. Deletes filter by both owner and work and remove all versions; a lost-lease completion or unknown response also schedules a current-source repair without restoring an obsolete owner. Old versions may remain in the collection. Retrieval overfetches at most 30 candidates and discards stale versions through SQL, so it does not promise exhaustive recall: many old versions can crowd out a current candidate. Monitor this and perform conservative cleanup using verified obsolete source hashes; never delete “all other versions” from a stale worker, which could erase a newer writer's result.

The transactional queue coalesces work changes by monotonically increasing requested version. Workers claim a 90-second lease, perform HTTP outside transactions and acknowledge only their claimed version. Failed jobs retry with bounded backoff; concurrent edits remain pending. Optional durable notifications contain only the work ID and version key; database polling remains the recovery path.

## History and structured queries

Initial history is the latest 40 messages; `/history?beforeSeq=...&limit=40` pages backward. State polling sends `afterSeq` and `historyEpoch`, returning only new messages plus the latest user status update. Reset increments the epoch so late responses and another tab's obsolete history cannot repopulate the cleared conversation. Model context uses a bounded recent window, not a claim of unlimited memory.

`list_works` uses parameterized SQL keyword matching across owned titles, stories, messages and lyrics, with literal wildcard escaping and 20-item pagination. It has no former 2000-work scanning cap. SQL keyword matching is not semantic retrieval and can still require a full scan within the owner's corpus; measure before adding full-text indexes.

## Isolated verification

Unit/integration tests use in-memory H2 and loopback fake embedding endpoints. `AgentSemanticQdrantIntegrationTest` only runs when `QF_TEST_QDRANT_URL` equals the isolated `http://127.0.0.1:16333`; it creates and removes a unique `test_agent_...` collection. It never reads production configuration. Do not run fixtures, backfills or automatic migrations against the production database for validation.
