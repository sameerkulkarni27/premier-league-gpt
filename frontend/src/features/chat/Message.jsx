import AnswerRenderer from './renderers/AnswerRenderer'

function formatTime(isoTimestamp) {
  const parsed = new Date(isoTimestamp)
  if (Number.isNaN(parsed.getTime())) return ''
  return parsed.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })
}

/** One question/answer turn in the conversation. */
export default function Message({ turn }) {
  return (
    <li className="message-turn">
      <div className="message question-message">
        <p>{turn.question}</p>
        <span className="message-time">{formatTime(turn.timestamp)}</span>
      </div>

      <div className="message answer-message" aria-live="polite">
        {turn.status === 'loading' && (
          <div className="loading-skeleton" role="status" aria-label="Loading answer" data-testid="loading">
            <span className="skeleton-line" />
            <span className="skeleton-line short" />
          </div>
        )}

        {turn.status === 'failed' && (
          <p className="error-message" role="alert">
            {turn.error || 'Something went wrong. Please try again.'}
          </p>
        )}

        {turn.status === 'succeeded' && <AnswerRenderer response={turn.response} />}
      </div>
    </li>
  )
}
