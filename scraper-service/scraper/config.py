"""
Shared, non-secret configuration for the scraper app: the supported
`league`/`data_type` values from PLAN.md and the league-slug -> ESPN
league-code mapping.
"""

# PLAN.md "Supported values (v1)": league slug (as used in our HTTP API)
# -> ESPN's internal league code (as used in ESPN's own URLs/APIs).
LEAGUE_TO_ESPN_CODE = {
    "premier-league": "eng.1",
}

DATA_TYPES = ("standings", "fixtures", "results")

MONGO_DB_NAME = "pitchquery"
MONGO_COLLECTION_NAME = "espn_snapshots"


def is_supported(league: str, data_type: str) -> bool:
    return league in LEAGUE_TO_ESPN_CODE and data_type in DATA_TYPES
