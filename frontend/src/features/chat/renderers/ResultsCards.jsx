function formatDate(isoDate) {
  const parsed = new Date(isoDate)
  if (Number.isNaN(parsed.getTime())) return isoDate
  return parsed.toLocaleString(undefined, {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  })
}

/**
 * Renders the "results" data shape from PLAN.md:
 * { results: [{ event_id, date, home_team, away_team, home_score,
 *              away_score, status }] }
 */
export default function ResultsCards({ data }) {
  const results = data && Array.isArray(data.results) ? data.results : []

  if (results.length === 0) {
    return <p className="empty-state">No results found.</p>
  }

  return (
    <div className="card-list" data-testid="results-cards">
      {results.map((result) => (
        <div className="answer-card result-card" key={result.event_id}>
          <div className="card-date">{formatDate(result.date)}</div>
          <div className="card-matchup">
            <span className="team home-team">{result.home_team}</span>
            <span className="score">
              {result.home_score} &ndash; {result.away_score}
            </span>
            <span className="team away-team">{result.away_team}</span>
          </div>
          <div className="card-meta">
            <span className={`status-badge status-${String(result.status).toLowerCase()}`}>
              {result.status}
            </span>
          </div>
        </div>
      ))}
    </div>
  )
}
