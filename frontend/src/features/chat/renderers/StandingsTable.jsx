/**
 * Renders the "standings" data shape from PLAN.md:
 * { table: [{ rank, team, played, won, drawn, lost, goals_for, goals_against,
 *             goal_diff, points, form: [...] }] }
 */
export default function StandingsTable({ data }) {
  const table = data && Array.isArray(data.table) ? data.table : []

  if (table.length === 0) {
    return <p className="empty-state">No standings data available.</p>
  }

  return (
    <div className="table-scroll">
      <table className="standings-table">
        <thead>
          <tr>
            <th>#</th>
            <th>Team</th>
            <th>P</th>
            <th>W</th>
            <th>D</th>
            <th>L</th>
            <th>GF</th>
            <th>GA</th>
            <th>GD</th>
            <th>Pts</th>
            <th>Form</th>
          </tr>
        </thead>
        <tbody>
          {table.map((row) => (
            <tr key={row.team_id ?? `${row.rank}-${row.team}`}>
              <td>{row.rank}</td>
              <td className="team-name">{row.team}</td>
              <td>{row.played}</td>
              <td>{row.won}</td>
              <td>{row.drawn}</td>
              <td>{row.lost}</td>
              <td>{row.goals_for}</td>
              <td>{row.goals_against}</td>
              <td>{row.goal_diff}</td>
              <td className="points">{row.points}</td>
              <td>
                <span className="form-chips">
                  {(row.form ?? []).map((result, idx) => (
                    <span key={idx} className={`form-chip form-${String(result).toLowerCase()}`}>
                      {result}
                    </span>
                  ))}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
