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
 * Renders the "fixtures" data shape from PLAN.md:
 * { fixtures: [{ event_id, date, home_team, away_team, venue, status }] }
 */
export default function FixturesCards({ data }) {
  const fixtures = data && Array.isArray(data.fixtures) ? data.fixtures : []

  if (fixtures.length === 0) {
    return <p className="empty-state">No upcoming fixtures found.</p>
  }

  return (
    <div className="card-list" data-testid="fixtures-cards">
      {fixtures.map((fixture) => (
        <div className="answer-card fixture-card" key={fixture.event_id}>
          <div className="card-date">{formatDate(fixture.date)}</div>
          <div className="card-matchup">
            <span className="team home-team">{fixture.home_team}</span>
            <span className="vs">vs</span>
            <span className="team away-team">{fixture.away_team}</span>
          </div>
          <div className="card-meta">
            {fixture.venue && <span className="venue">{fixture.venue}</span>}
            <span className={`status-badge status-${String(fixture.status).toLowerCase()}`}>
              {fixture.status}
            </span>
          </div>
        </div>
      ))}
    </div>
  )
}
