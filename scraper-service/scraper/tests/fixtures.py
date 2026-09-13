"""Trimmed-down real ESPN payload shapes (captured from
site.api.espn.com during development) used to drive the parser tests
without any network access."""

STANDINGS_PAYLOAD = {
    "children": [
        {
            "standings": {
                "season": 2026,
                "seasonDisplayName": "2026-27 English Premier League",
                "entries": [
                    {
                        "team": {
                            "id": "359",
                            "displayName": "Arsenal",
                            "shortDisplayName": "Arsenal",
                        },
                        "stats": [
                            {"name": "gamesPlayed", "value": 5.0},
                            {"name": "losses", "value": 0.0},
                            {"name": "pointDifferential", "value": 9.0},
                            {"name": "points", "value": 13.0},
                            {"name": "pointsAgainst", "value": 3.0},
                            {"name": "pointsFor", "value": 12.0},
                            {"name": "ties", "value": 1.0},
                            {"name": "wins", "value": 4.0},
                            {"name": "rank", "value": 1.0},
                        ],
                    },
                    {
                        "team": {
                            "id": "364",
                            "displayName": "Liverpool",
                            "shortDisplayName": "Liverpool",
                        },
                        "stats": [
                            {"name": "gamesPlayed", "value": 5.0},
                            {"name": "losses", "value": 1.0},
                            {"name": "pointDifferential", "value": 5.0},
                            {"name": "points", "value": 10.0},
                            {"name": "pointsAgainst", "value": 5.0},
                            {"name": "pointsFor", "value": 10.0},
                            {"name": "ties", "value": 1.0},
                            {"name": "wins", "value": 3.0},
                            {"name": "rank", "value": 2.0},
                        ],
                    },
                ],
            }
        }
    ]
}

SCOREBOARD_PAYLOAD = {
    "season": {"type": 14308, "year": 2026},
    "events": [
        {
            "id": "401879282",
            "date": "2026-09-13T13:00Z",
            "season": {"year": 2026},
            "status": {"type": {"state": "post", "name": "STATUS_FULL_TIME"}},
            "competitions": [
                {
                    "venue": {"fullName": "Coventry Building Society Arena"},
                    "competitors": [
                        {
                            "homeAway": "home",
                            "team": {"displayName": "Coventry City"},
                            "score": "0",
                        },
                        {
                            "homeAway": "away",
                            "team": {"displayName": "Brighton & Hove Albion"},
                            "score": "5",
                        },
                    ],
                }
            ],
        },
        {
            "id": "401879280",
            "date": "2026-09-20T14:00Z",
            "season": {"year": 2026},
            "status": {"type": {"state": "pre", "name": "STATUS_SCHEDULED"}},
            "competitions": [
                {
                    "venue": {"fullName": "Emirates Stadium"},
                    "competitors": [
                        {
                            "homeAway": "home",
                            "team": {"displayName": "Arsenal"},
                            "score": "0",
                        },
                        {
                            "homeAway": "away",
                            "team": {"displayName": "Chelsea"},
                            "score": "0",
                        },
                    ],
                }
            ],
        },
    ],
}
