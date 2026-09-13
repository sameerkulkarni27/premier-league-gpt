import ChatInput from './ChatInput'
import MessageList from './MessageList'

export default function ChatWindow() {
  return (
    <div className="chat-window">
      <header className="chat-header">
        <h1>Pitch Query</h1>
        <p>Ask anything about the Premier League.</p>
      </header>
      <MessageList />
      <ChatInput />
    </div>
  )
}
