# Pitch Query — frontend

React + Redux Toolkit chat UI for Pitch Query. Sends natural-language
questions to api-gateway's `POST /api/ask` and renders the structured
response (standings table, fixture/result cards, or plain text) per the
contract in the repo root's `PLAN.md` ("Contract: frontend ↔ api-gateway").

## Stack

- [Vite](https://vite.dev/) + React 19
- [Redux Toolkit](https://redux-toolkit.js.org/) (`createSlice` +
  `createAsyncThunk`) for the chat state and the `/api/ask` request
- [Vitest](https://vitest.dev/) + [React Testing
  Library](https://testing-library.com/react) for tests
- [oxlint](https://oxc.rs/docs/guide/usage/linter.html) for linting

## Getting started

```bash
npm install
cp .env.example .env   # adjust VITE_API_GATEWAY_BASE_URL if needed
npm run dev
```

The app expects api-gateway's `POST /api/ask` at
`VITE_API_GATEWAY_BASE_URL` (default `http://localhost:8080` if the env var
is unset). No backend is required to develop the UI itself — all rendering
logic is covered by tests that mock `fetch` with response shapes lifted
directly from `PLAN.md`.

## Scripts

| Command | Description |
|---|---|
| `npm run dev` | Start the Vite dev server with HMR |
| `npm run build` | Production build to `dist/` |
| `npm run preview` | Preview the production build locally |
| `npm test` | Run the Vitest suite once (CI mode) |
| `npm run test:watch` | Run Vitest in watch mode |
| `npm run lint` | Run oxlint |

## Project structure

```
src/
  app/
    store.js               Redux store (single `chat` slice)
  features/chat/
    chatSlice.js            askQuestion thunk + conversation state
    chatSlice.test.js
    ChatWindow.jsx           top-level layout (header + list + input)
    MessageList.jsx          renders the conversation
    Message.jsx              one question/answer turn (loading/error/answer)
    ChatInput.jsx             text input + send button
    renderers/
      AnswerRenderer.jsx      picks a renderer by `answer_type`
      StandingsTable.jsx      "standings" -> table
      FixturesCards.jsx       "fixtures" -> cards
      ResultsCards.jsx        "results" -> cards
      TextBubble.jsx          "text" -> plain summary
      AnswerRenderer.test.jsx one render test per answer_type
  App.jsx / App.test.jsx      wires it together; App.test covers the full
                               submit -> loading -> answer flow end to end
```

## Notes on the contract

- Request: `POST /api/ask` with `{ "question": string }`.
- Response: `{ answer_type, league, summary, data, generated_at }` where
  `answer_type` is one of `standings | fixtures | results | text`, and
  `data` is `null` for `text` answers. See `PLAN.md` at the repo root for the
  exact `data` shape per `answer_type`.
- Error responses (`4xx`/`5xx`) are `{ "error": "..." }` — rendered as an
  inline error bubble, never a blank screen or crash. Network failures
  (fetch rejecting) are handled the same way.

This frontend does not call api-gateway internals, Mongo, or ESPN directly —
see the "Service boundaries" table in `PLAN.md`.
