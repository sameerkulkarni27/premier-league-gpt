import StandingsTable from './StandingsTable'
import FixturesCards from './FixturesCards'
import ResultsCards from './ResultsCards'
import TextBubble from './TextBubble'

/**
 * Renders one /api/ask response (see PLAN.md, "Contract: frontend <->
 * api-gateway"): always shows `summary` as a caption, then the structured
 * `data` rendered according to `answer_type`.
 */
export default function AnswerRenderer({ response }) {
  if (!response) return null

  const { answer_type: answerType, summary, data } = response

  // "text" answers have no structured data -- the summary IS the answer, so
  // it's rendered once via TextBubble rather than as a caption + repeat.
  const hasStructuredContent = ['standings', 'fixtures', 'results'].includes(answerType)

  return (
    <div className="answer" data-testid="answer" data-answer-type={answerType}>
      {hasStructuredContent && summary && <p className="answer-summary">{summary}</p>}
      {answerType === 'standings' && <StandingsTable data={data} />}
      {answerType === 'fixtures' && <FixturesCards data={data} />}
      {answerType === 'results' && <ResultsCards data={data} />}
      {answerType === 'text' && <TextBubble summary={summary} />}
      {!hasStructuredContent && answerType !== 'text' && (
        <p className="empty-state">Unrecognized answer type &quot;{String(answerType)}&quot;.</p>
      )}
    </div>
  )
}
