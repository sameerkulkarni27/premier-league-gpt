import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import AnswerRenderer from './AnswerRenderer'

describe('AnswerRenderer', () => {
  it('renders nothing for a null response', () => {
    const { container } = render(<AnswerRenderer response={null} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders a standings table with summary caption', () => {
    const response = {
      answer_type: 'standings',
      league: 'premier-league',
      summary: 'Arsenal are 3rd in the Premier League with 11 points from 5 games.',
      data: {
        table: [
          {
            rank: 1,
            team: 'Liverpool',
            team_id: '364',
            played: 5,
            won: 4,
            drawn: 1,
            lost: 0,
            goals_for: 12,
            goals_against: 3,
            goal_diff: 9,
            points: 13,
            form: ['W', 'W', 'D', 'W', 'W'],
          },
        ],
      },
      generated_at: '2026-09-13T19:05:00Z',
    }

    render(<AnswerRenderer response={response} />)

    expect(screen.getByText(/Arsenal are 3rd/)).toBeInTheDocument()
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getByText('Liverpool')).toBeInTheDocument()
    expect(screen.getByText('13')).toBeInTheDocument()
  })

  it('renders fixture cards', () => {
    const response = {
      answer_type: 'fixtures',
      league: 'premier-league',
      summary: "Arsenal's next fixture is against Chelsea.",
      data: {
        fixtures: [
          {
            event_id: '678889',
            date: '2026-09-20T14:00:00Z',
            home_team: 'Arsenal',
            away_team: 'Chelsea',
            venue: 'Emirates Stadium',
            status: 'scheduled',
          },
        ],
      },
      generated_at: '2026-09-13T19:05:00Z',
    }

    render(<AnswerRenderer response={response} />)

    expect(screen.getByTestId('fixtures-cards')).toBeInTheDocument()
    expect(screen.getByText('Arsenal')).toBeInTheDocument()
    expect(screen.getByText('Chelsea')).toBeInTheDocument()
    expect(screen.getByText('Emirates Stadium')).toBeInTheDocument()
    expect(screen.getByText('scheduled')).toBeInTheDocument()
  })

  it('renders result cards with scores', () => {
    const response = {
      answer_type: 'results',
      league: 'premier-league',
      summary: 'Man City beat Everton 3-1.',
      data: {
        results: [
          {
            event_id: '678870',
            date: '2026-09-13T14:00:00Z',
            home_team: 'Man City',
            away_team: 'Everton',
            home_score: 3,
            away_score: 1,
            status: 'final',
          },
        ],
      },
      generated_at: '2026-09-13T19:05:00Z',
    }

    render(<AnswerRenderer response={response} />)

    expect(screen.getByTestId('results-cards')).toBeInTheDocument()
    expect(screen.getByText('Man City')).toBeInTheDocument()
    expect(screen.getByText('Everton')).toBeInTheDocument()
    expect(screen.getByText((text) => text.includes('3') && text.includes('1'), { selector: '.score' })).toBeInTheDocument()
    expect(screen.getByText('final')).toBeInTheDocument()
  })

  it('renders a plain text bubble for answer_type "text" with null data', () => {
    const response = {
      answer_type: 'text',
      league: 'premier-league',
      summary: 'Mikel Arteta is the manager of Arsenal.',
      data: null,
      generated_at: '2026-09-13T19:05:00Z',
    }

    render(<AnswerRenderer response={response} />)

    expect(screen.getByText('Mikel Arteta is the manager of Arsenal.')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('renders empty states instead of crashing when structured data is missing', () => {
    const response = {
      answer_type: 'standings',
      summary: 'Standings unavailable.',
      data: null,
    }

    render(<AnswerRenderer response={response} />)

    expect(screen.getByText(/No standings data available/)).toBeInTheDocument()
  })
})
