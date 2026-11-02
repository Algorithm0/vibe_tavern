# Native client

The app's front door is now a Jetpack Compose client (`ClientActivity`). The original
launcher screen (`MainActivity`: server log, launcher updates, legacy migration) is still
there and opens from the connect screen.

## Modes

* **This device** – the client starts the bundled server through the existing
  `ServerService` (payload extraction included) and talks to `http://127.0.0.1:8787`
  without a token.
* **Remote server** – address + Mobile Access token (or the whole `http://ip:8787/#token=…`
  link from the web UI). Tokens are stored AES-GCM encrypted with an Android Keystore key.

## Layout

| Module | Contents | Runs on |
| --- | --- | --- |
| `core/model` | DTOs, `SessionPatch` merge | JVM |
| `core/network` | `TavernApi`, OkHttp engine, SSE, PNG card reader, address parser | JVM |
| `core/data` | `ChatController`, `LibraryController`, server profiles, character draft diff, RP text formatter | JVM |
| `app` (`…/launcher/client`) | Keystore cipher, file store, local-server manager, `AppViewModel`, Compose screens | Android |

Everything with logic lives in the JVM modules and is unit tested (`./gradlew :core:model:test
:core:network:test :core:data:test`). The Android layer is thin glue and UI.

## Covered so far

Characters: list, search, create, edit (all card fields, avatar), duplicate, archive (with
Undo), delete, import (PNG / JSON / markdown), export PNG. Chats: per-character list, new,
delete, streaming replies with Stop, reasoning blocks, swipes (including regenerate on the
last one), continue, generate reply, edit/delete messages, branches, fork, alternate
greetings, rename, clear. Active provider can be switched; creating providers, lorebooks,
personas, presets, summaries and the rest are still in the web UI ("Open web UI").

## Known gaps

* No attachments in the composer yet (they display).
* No QR scanner: paste the Mobile Access link.
* Archived characters cannot be listed again (the server has no endpoint); the Undo
  snackbar is the only way back from the app.
* Localisation: English and Russian for the client screens.
