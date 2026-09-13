import { useSelector } from 'react-redux'
import Message from './Message'
import { selectConversation } from './chatSlice'

export default function MessageList() {
  const conversation = useSelector(selectConversation)

  if (conversation.length === 0) {
    return (
      <div className="message-list empty">
        <p className="empty-state">
          Ask about Premier League standings, fixtures, results, or anything else about the league.
        </p>
      </div>
    )
  }

  return (
    <ul className="message-list">
      {conversation.map((turn) => (
        <Message key={turn.id} turn={turn} />
      ))}
    </ul>
  )
}
