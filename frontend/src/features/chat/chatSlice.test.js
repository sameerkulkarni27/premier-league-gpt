import { configureStore } from '@reduxjs/toolkit'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import chatReducer, { askQuestion, selectConversation, selectChatStatus } from './chatSlice'

function buildStore() {
  return configureStore({ reducer: { chat: chatReducer } })
}

describe('chatSlice', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('starts with an empty conversation and idle status', () => {
    const store = buildStore()
    expect(selectConversation(store.getState())).toEqual([])
    expect(selectChatStatus(store.getState())).toBe('idle')
  })

  it('appends a loading turn while the request is pending', () => {
    let resolveFetch
    global.fetch.mockReturnValue(
      new Promise((resolve) => {
        resolveFetch = resolve
      })
    )

    const store = buildStore()
    const promise = store.dispatch(askQuestion('Where does Arsenal stand?'))

    const state = store.getState()
    expect(selectChatStatus(state)).toBe('loading')
    expect(selectConversation(state)).toHaveLength(1)
    expect(selectConversation(state)[0]).toMatchObject({
      question: 'Where does Arsenal stand?',
      status: 'loading',
      response: null,
      error: null,
    })

    // avoid an unhandled rejection/leak once the test finishes
    resolveFetch({ ok: true, json: async () => ({}) })
    return promise
  })

  it('stores the response on success (standings shape from PLAN.md)', async () => {
    const payload = {
      answer_type: 'standings',
      league: 'premier-league',
      summary: 'Arsenal are 3rd in the Premier League with 11 points from 5 games.',
      data: {
        table: [
          {
            rank: 3,
            team: 'Arsenal',
            team_id: '359',
            played: 5,
            won: 3,
            drawn: 2,
            lost: 0,
            goals_for: 10,
            goals_against: 4,
            goal_diff: 6,
            points: 11,
            form: ['W', 'D', 'W', 'D', 'W'],
          },
        ],
      },
      generated_at: '2026-09-13T19:05:00Z',
    }
    global.fetch.mockResolvedValue({ ok: true, json: async () => payload })

    const store = buildStore()
    await store.dispatch(askQuestion('Where does Arsenal stand in the Premier League table?'))

    const state = store.getState()
    expect(selectChatStatus(state)).toBe('succeeded')
    const [turn] = selectConversation(state)
    expect(turn.status).toBe('succeeded')
    expect(turn.error).toBeNull()
    expect(turn.response).toEqual(payload)

    expect(global.fetch).toHaveBeenCalledWith(
      expect.stringContaining('/api/ask'),
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ question: 'Where does Arsenal stand in the Premier League table?' }),
      })
    )
  })

  it('stores the error message from a { error } response body', async () => {
    global.fetch.mockResolvedValue({
      ok: false,
      status: 500,
      json: async () => ({ error: 'LLM tool call failed' }),
    })

    const store = buildStore()
    await store.dispatch(askQuestion('Who scored the most goals?'))

    const state = store.getState()
    expect(selectChatStatus(state)).toBe('failed')
    const [turn] = selectConversation(state)
    expect(turn.status).toBe('failed')
    expect(turn.response).toBeNull()
    expect(turn.error).toBe('LLM tool call failed')
  })

  it('stores a network error when fetch itself rejects', async () => {
    global.fetch.mockRejectedValue(new TypeError('Failed to fetch'))

    const store = buildStore()
    await store.dispatch(askQuestion('Any question'))

    const state = store.getState()
    expect(selectChatStatus(state)).toBe('failed')
    const [turn] = selectConversation(state)
    expect(turn.error).toBe('Failed to fetch')
  })

  it('handles multiple turns independently', async () => {
    global.fetch
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ answer_type: 'text', summary: 'Arteta is the manager.', data: null }),
      })
      .mockResolvedValueOnce({ ok: false, status: 502, json: async () => ({ error: 'upstream failure' }) })

    const store = buildStore()
    await store.dispatch(askQuestion('Who is the manager?'))
    await store.dispatch(askQuestion('What about now?'))

    const conversation = selectConversation(store.getState())
    expect(conversation).toHaveLength(2)
    expect(conversation[0].status).toBe('succeeded')
    expect(conversation[1].status).toBe('failed')
  })
})
