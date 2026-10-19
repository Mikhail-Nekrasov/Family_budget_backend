# Personal offline synchronization

This protocol synchronizes the authenticated user's personal budget. Group operations
use the online APIs. It does not download the server's users, passwords, roles,
memberships, invitations, or other users' account balances.

## Command API

Every queued action has a client-generated command UUID. Reuse the same command UUID
and payload after network failures; never generate a new UUID for a retry.
Entity UUIDs and command UUIDs are different identifiers.

| Method | Path | Request DTO |
| --- | --- | --- |
| POST | `/api/sync/accounts/{commandId}` | `CreateAccountRequest` |
| PUT | `/api/sync/accounts/{commandId}` | `SyncUpdateAccountRequest` |
| POST | `/api/sync/accounts/{commandId}/delete` | `SyncDeleteAccountRequest` |
| POST | `/api/sync/categories/{commandId}` | `CreateCategoryRequest` |
| POST | `/api/sync/categories/{commandId}/delete` | `SyncDeleteCategoryRequest` |
| POST | `/api/sync/entries/{commandId}` | `SyncCreateEntryRequest` |
| PUT | `/api/sync/entries/{commandId}` | `SyncUpdateEntryRequest` |
| POST | `/api/sync/entries/{commandId}/delete` | `SyncDeleteEntryRequest` |
| POST | `/api/sync/transfers/{commandId}` | `SyncCreateTransferRequest` |

Sync creation requires entity UUIDs. Entry and transfer relationships use account/category
UUIDs. Updating and deleting versioned entities requires `expectedVersion`.
Transfers are immutable; category editing is not part of this protocol yet.

Successful results are saved atomically with the change. A completed command always
returns its original result, including after subsequent changes or sharing the account.
A reused command ID with another payload returns 409. Failed commands are not stored as
completed. A conflict must be resolved against current data; do not silently replace
`expectedVersion` and retry. For version conflicts, ask the user to review the change.
Cached command responses are receipts, not the current authoritative entity state.

The personal queue rejects commands targeting an account currently shared with any
group, including a shared source/destination of a transfer and both the original and
new account of an edited entry. Use ordinary online APIs for those actions. Ownership
is still required by the personal sync endpoints. This check is made at execution time;
it is not a new locking scheme for simultaneous sharing and financial operations.

Delete rules still apply: accounts with entries/transfers and categories with entries
cannot be deleted. Deletion tombstones prevent stale clients from recreating the same
UUID through the common services.

## Authentication

Use the existing session login flow and CSRF token. All POST/PUT operations require
CSRF, including starting and completing a download. GET pages require the authenticated
session. Obtain a fresh CSRF token after login. Tokens and passwords do not appear in
sync download payloads.

## Download API

### 1. Create an immutable export

```
POST /api/sync/downloads
Content-Type: application/json
X-CSRF-TOKEN: ...
```

For the first download, or recovery after cursor expiry:

```json
{"cursor": null}
```

For later downloads, use the cursor saved after the previous download was fully applied:

```json
{"cursor": "previous-nextCursor-uuid"}
```

Response (201):

```json
{
  "downloadId": "export-uuid",
  "mode": "FULL",
  "itemCount": 1250,
  "pageSize": 200,
  "pagesExpireAt": "...",
  "cursorExpiresAt": "..."
}
```

The actual UUIDs in requests must be valid UUID strings. Placeholders here are explanatory.
`FULL` is a complete authoritative personal dataset. `DELTA` contains the final current
state of changed entities and markers for entities that left the dataset. It is not an
operation history or audit log; several edits can be coalesced into one item.

### 2. Read every page

```
GET /api/sync/downloads/{downloadId}?after=0
```

Example item:

```json
{
  "position": 1,
  "entityType": "ENTRY",
  "syncId": "entity-uuid",
  "action": "UPSERT",
  "data": {
    "id": 42,
    "syncId": "entity-uuid",
    "version": 2,
    "title": "Lunch",
    "type": "EXPENSE",
    "amount": 12.50,
    "dateTime": "2026-10-19T12:00:00",
    "description": "",
    "accountSyncId": "account-uuid",
    "categorySyncId": "category-uuid"
  }
}
```

Use the returned `nextAfter` verbatim for the next page. Do not calculate an offset.
Items are ordered by entity type and UUID, not by operation time. The position belongs
to this export only and is not an incremental synchronization cursor.

A deleted entity has `action: "DELETE"`, its type/UUID, and `data: null`.
Deletion is idempotent, even if the phone has never seen that UUID.

Only the last page returns a non-null `nextCursor`; its `nextAfter` is null. An empty
export still has a last page with an empty item list and a valid next cursor.
Re-reading a page returns the same exported items even if live data changes meanwhile.

Payloads:

- ACCOUNT: server ID, UUID, version, name, amount, description, `shared`.
- CATEGORY: server ID, UUID, version, name, type, `readOnly`.
- ENTRY: server ID, UUID, version, title, type, amount, local date/time, description,
  account UUID and category UUID.
- TRANSFER: server ID, UUID, amount, local date/time, description, source/destination
  UUIDs and names, `fromAccountOwned`, `toAccountOwned`.

A category referenced by an owned entry can be included with `readOnly: true` even if
someone else owns it. A cross-user transfer includes counterpart account labels and
UUIDs for displaying the user's transaction, but not the other account's balance or
private history. The counterpart need not exist in the local owned-account table.
Do not model those external references as mandatory references to an owned account.
Account names used in transfer display and changes to account sharing are published too.

### 3. Apply atomically and save the cursor

Stage all pages before changing the visible local server dataset. Within one local
transaction:

1. For FULL, replace only the previous **server mirror** of this user's personal data.
   Do not erase pending local changes or the outbox.
2. Apply account/category upserts, then entry/transfer upserts.
3. Apply entry/transfer deletions before account/category deletions. This order also
   supports categories that disappear when their last local reference is removed.
4. Reconcile or overlay pending local edits separately; never overwrite them blindly.
5. Save `nextCursor` atomically with the applied server mirror.

Do not update the cursor after a partially downloaded or partially applied export.
A crash before the local transaction commits must leave the old mirror and cursor intact.
The server cursor does not acknowledge receipt of all pages; enforcing atomic application
is the client's responsibility.

After committing locally, release server-side pages:

```
POST /api/sync/downloads/{downloadId}/complete
X-CSRF-TOKEN: ...
```

Response: 204. Repeating completion is safe while the download header is retained.
Completion removes page data but keeps the checkpoint usable for the next delta.
Do not complete until the local transaction has committed. If only the completion
request fails, retain the local cursor and retry completion later.

## Client workflow

1. Keep a durable personal SQLite database, server mirror, and outbox partitioned by user.
2. Submit queued account/category creations before entries or transfers referencing them.
3. Keep failed/conflicting commands pending for resolution. Continue only independent work.
4. Download from the last fully applied cursor, stage pages, and reconcile the mirror/outbox.
5. Apply the download and cursor together, then complete the download.

Shared accounts may be displayed from the last downloaded snapshot but must be read-only
without an internet connection. Their operations use the online API, then a new download.
The downloaded `shared` flag helps the UI; the server independently checks queued commands.

Local-only use is not time-limited. A user returning after years offline can still upload
their durable queue and request a new FULL download. Account identity, onboarding of guest
local data, SQLite transactions, and conflict UI must be implemented by the mobile client.

## Cursor correctness and PostgreSQL

V10 adds a small key-only transactional change journal through PostgreSQL triggers.
Writes through web, normal REST, sync REST, and ordinary SQL all enter the same journal.
A rolled-back write leaves no journal row. TRUNCATE, disabling triggers, and restoring an
older production database are administrative operations outside this protocol.

Download creation runs in REPEATABLE READ and materializes JSON pages in PostgreSQL.
The checkpoint stores `pg_current_snapshot()`, not MAX(sequence) or a wall-clock timestamp.
Deltas include keys whose transaction was not visible in the previous snapshot. This
captures transactions which started earlier but committed later. Current row states and
the journal are read from one consistent snapshot; pages are immutable after creation.
No open transaction, exported snapshot, or database connection is retained for paging.
The implementation targets PostgreSQL 17+; H2 is not used to emulate its snapshot semantics.

Database restore to an earlier point invalidates cursors held by clients. Recovery must
force a fresh FULL download and reconcile the outbox before accepting further edits.
Do not treat a restored old UUID checkpoint as a valid continuation of a later timeline.

## Retention and cost

- Materialized pages: up to 24 hours, or earlier after completion.
- Cursor headers: 7 days. Expiry does not limit offline use; it requires a new FULL download.
- Change journal: entries older than 30 days are pruned only when already visible to every
  retained cursor for that user. Events still needed by a valid cursor are retained.
- Tombstones and successful commands: not automatically deleted. This preserves the
  existing guarantees for very old outboxes and retries. A future bounded policy requires
  an explicit client/device reset protocol; do not add a simple TTL to these tables.
- Cleanup runs hourly; disable with `sync.download.cleanup.enabled=false` in test profiles.

Exports consume temporary database space. A FULL export temporarily stores another copy
of the user's exported JSON; complete it promptly. Deltas store changed keys' current
payloads only. The journal stores keys/transaction IDs, not full operation payload history.
Multiple unfinished full exports and accumulated commands still count against database
quota; monitor their size before expanding the user base. Existing load-test results do
not measure the new download endpoints or trigger overhead; benchmark those separately.

Expired pages return 410. An expired cursor still present in the database returns 410;
a cursor already cleaned up, unknown UUID, or another user's UUID returns 404. On expired
or missing cursor, start FULL with null, preserving the local outbox. Unexpected 401/403
requires authentication/CSRF handling rather than clearing the user's local data.

## Tests

Run Docker Desktop, then:

```sh
./mvnw clean verify
```

New PostgreSQL cases cover command replay and category delete conflicts, shared account
queue restrictions, full/delta exports, immutable pagination, rollback, a late-committing
transaction, cross-user privacy, referenced categories, transfer labels, expiry, completion,
CSRF/session requirements, and retention of events needed by active cursors.

The mobile client, standard category codes/renaming policy, subscriptions, and permanent
group history after account deletion are separate work. This package does not implement them.
