import { createAsyncThunk, createSlice } from '@reduxjs/toolkit'

const DEFAULT_BASE_URL = 'http://localhost:8080'

/**
 * Resolves the api-gateway base URL from VITE_API_GATEWAY_BASE_URL (see
 * frontend/.env.example), falling back to the docker-compose default.
 */
export const getApiBaseUrl = () => {
  const envUrl =
    typeof import.meta !== 'undefined' && import.meta.env
      ? import.meta.env.VITE_API_GATEWAY_BASE_URL
      : undefined
  return envUrl || DEFAULT_BASE_URL
}

/**
 * POSTs { question } to /api/ask on api-gateway. See PLAN.md, section
 * "Contract: frontend <-> api-gateway", for the request/response shape.
 */
export const askQuestion = createAsyncThunk('chat/askQuestion', async (question, { rejectWithValue }) => {
  let res
  try {
    res = await fetch(`${getApiBaseUrl()}/api/ask`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question }),
    })
  } catch (err) {
    return rejectWithValue(err instanceof Error ? err.message : 'Network error')
  }

  let body = null
  try {
    body = await res.json()
  } catch {
    body = null
  }

  if (!res.ok) {
    const message = body && body.error ? body.error : `Request failed with status ${res.status}`
    return rejectWithValue(message)
  }

  if (body && body.error) {
    return rejectWithValue(body.error)
  }

  return body
})

const initialState = {
  // Each turn: { id, question, response, error, status, timestamp }
  conversation: [],
  status: 'idle', // 'idle' | 'loading' | 'succeeded' | 'failed' -- reflects the most recent turn
}

const chatSlice = createSlice({
  name: 'chat',
  initialState,
  reducers: {},
  extraReducers: (builder) => {
    builder
      .addCase(askQuestion.pending, (state, action) => {
        state.status = 'loading'
        state.conversation.push({
          id: action.meta.requestId,
          question: action.meta.arg,
          response: null,
          error: null,
          status: 'loading',
          timestamp: new Date().toISOString(),
        })
      })
      .addCase(askQuestion.fulfilled, (state, action) => {
        state.status = 'succeeded'
        const turn = state.conversation.find((t) => t.id === action.meta.requestId)
        if (turn) {
          turn.response = action.payload
          turn.status = 'succeeded'
        }
      })
      .addCase(askQuestion.rejected, (state, action) => {
        state.status = 'failed'
        const turn = state.conversation.find((t) => t.id === action.meta.requestId)
        if (turn) {
          turn.error = action.payload || action.error.message || 'Something went wrong'
          turn.status = 'failed'
        }
      })
  },
})

export default chatSlice.reducer

export const selectConversation = (state) => state.chat.conversation
export const selectChatStatus = (state) => state.chat.status
