import { useState } from 'react'
import { useDispatch, useSelector } from 'react-redux'
import { askQuestion, selectChatStatus } from './chatSlice'

export default function ChatInput() {
  const [question, setQuestion] = useState('')
  const dispatch = useDispatch()
  const status = useSelector(selectChatStatus)
  const isLoading = status === 'loading'

  function handleSubmit(event) {
    event.preventDefault()
    const trimmed = question.trim()
    if (!trimmed || isLoading) return
    dispatch(askQuestion(trimmed))
    setQuestion('')
  }

  return (
    <form className="chat-input" onSubmit={handleSubmit}>
      <input
        type="text"
        value={question}
        onChange={(event) => setQuestion(event.target.value)}
        placeholder="Ask about the Premier League..."
        aria-label="Ask a question"
        disabled={isLoading}
      />
      <button type="submit" disabled={isLoading || !question.trim()}>
        {isLoading ? 'Asking...' : 'Send'}
      </button>
    </form>
  )
}
