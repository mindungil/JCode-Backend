# v2.0.1 local release changes

## Course creation contract

- `POST /api/courses` requires `Idempotency-Key`: 16-128 ASCII letters,
  digits, dots, underscores or hyphens. Generate a UUID once per creation intent.
- Retain the key and payload when the response is lost. Retrying the same intent
  returns the same course; a changed payload returns `CREATION_REQUEST_CHANGED`.
- The key is scoped to the authenticated creator. Different keys represent
  different intents, including legitimately identical course names.
- A replay does not return or rotate the plaintext enrollment code. Use the
  existing explicit code-reissue operation when necessary.
- Replaying an ended/archived course returns `CREATION_REQUEST_FINISHED`.
  The client may clear that intent but must not automatically create another course.
- Cached clients without the header must refresh. Deploy the matching frontend.

## Workspace creation and database compatibility

- Membership row locks serialize creation for one owner. Creation and inspector
  transactions use READ COMMITTED to read the committed result after waiting.
  This avoids stale snapshot conflicts with MariaDB snapshot isolation enabled.
- Queue claiming also uses READ COMMITTED; other transactions and the database
  global isolation setting are unchanged.
- V13 uniquely guards non-archived STANDARD/SNAPSHOT instance keys. Before applying,
  verify there are no active duplicates; migration deliberately fails rather than
  deleting or guessing which user workspace to keep.
- DELETE_PENDING/DELETE_FAILED instances cannot be reused as successful launches.
- V14 adds creator-scoped request receipts. V13/V14 are additive, not data resets.

## Access and capabilities

- Students use their own normal workspace under assignment time-window policy.
- Course managers can inspect students' assignments and preview their own through
  the read-only inspector. Self-preview still requires manager authorization.
  It never grants student owners an inspector bypass or a writable pre-start mount.
- Demoting a course manager invalidates profiles and expires their inspector and
  snapshot instances. Students cannot inspect another student's workspace.
- LAB means IDE plus VNC, not Jupyter. V15 clears the unsupported LAB Jupyter flag.
  CUSTOM retains explicit Jupyter support for images that actually provide it.
- V15 is a capability correction; rollback must account for that change instead
  of blindly restoring an older application's assumptions.

No Git commit or Git push is part of this local release operation.
