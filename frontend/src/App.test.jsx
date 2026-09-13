import { configureStore } from '@reduxjs/toolkit'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Provider } from 'react-redux'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import chatReducer from './features/chat/chatSlice'

function renderApp() {
  const store = configureStore({ reducer: { chat: chatReducer } })
  render(
    <Provider store={store}>
      <App />
    </Provider>
  )
}

describe('App (chat flow)', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('submits a question, shows a loading state, then renders the structured answer', async () => {
    let resolveFetch
    global.fetch.mockReturnValue(
      new Promise((resolve) => {
        resolveFetch = resolve
      })
    )

    const user = userEvent.setup()
    renderApp()

    const input = screen.getByLabelText('Ask a question')
    await user.type(input, 'Where does Arsenal stand?')
    await user.click(screen.getByRole('button', { name: /send/i }))

    expect(screen.getByText('Where does Arsenal stand?')).toBeInTheDocument()
    expect(screen.getByTestId('loading')).toBeInTheDocument()
    // the input clears and disables while the request is in flight
    expect(input).toHaveValue('')

    resolveFetch({
      ok: true,
      json: async () => ({
        answer_type: 'text',
        league: 'premier-league',
        summary: 'Mikel Arteta is the manager of Arsenal.',
        data: null,
        generated_at: '2026-09-13T19:05:00Z',
      }),
    })

    await waitFor(() => expect(screen.queryByTestId('loading')).not.toBeInTheDocument())
    expect(screen.getByText('Mikel Arteta is the manager of Arsenal.')).toBeInTheDocument()
  })

  it('shows an error message instead of crashing on a failed request', async () => {
    global.fetch.mockResolvedValue({
      ok: false,
      status: 500,
      json: async () => ({ error: 'api-gateway is unreachable' }),
    })

    const user = userEvent.setup()
    renderApp()

    await user.type(screen.getByLabelText('Ask a question'), 'Who won the title last year?')
    await user.click(screen.getByRole('button', { name: /send/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent('api-gateway is unreachable')
  })
})
