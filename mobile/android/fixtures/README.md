# Captured server responses

Real responses from a running Vibe Tavern server (a mock OpenAI-compatible provider
stood in for the model), saved as-is. The unit tests in `core/model` and `core/network`
decode them, so the DTOs follow what the server actually sends rather than the partly
stale `docs/architecture/api-reference.md`.

| File | Endpoint |
| --- | --- |
| `bootstrap_initial.json`, `bootstrap_full.json` | `GET /api/bootstrap` (empty server / server with chats) |
| `chat_get.json`, `chat_create.json`, `chat_rename.json`, `chat_fork.json` | chat reads and mutations |
| `chat_after_send.json`, `chat_after_regen.json` | chat state after a send / regenerate |
| `message_edit.json`, `message_delete.json`, `message_branch.json`, `variant_select.json`, `greeting_index.json` | message mutations |
| `character_create.json`, `character_patch.json`, `character_duplicate.json`, `import_json.json`, `avatar_upload.json` | character endpoints |
| `provider_create.json` | `POST /api/providers` |
| `sse_send.txt`, `sse_regenerate.txt`, `sse_error.txt` | raw `text/event-stream` bodies of the generation streams |

To refresh one after a server change, call the endpoint on a local server and overwrite
the file; `DtoFixtureTest` and `VibeTavernApiTest` then show what drifted.
