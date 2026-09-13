"""
MongoDB integration for the `espn_snapshots` collection (PLAN.md "MongoDB
schema" section). Wrapped in a small `SnapshotStore` class rather than bare
module functions so tests can inject a mongomock client instead of a real
`pymongo.MongoClient` -- see scraper/tests/test_mongo.py.
"""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from django.conf import settings
from pymongo import ASCENDING, MongoClient
from pymongo.collection import Collection

from .config import MONGO_COLLECTION_NAME, MONGO_DB_NAME


class SnapshotStore:
    def __init__(self, client: MongoClient | None = None):
        self._client = client or MongoClient(settings.MONGO_URI)
        self._collection: Collection = self._client[MONGO_DB_NAME][MONGO_COLLECTION_NAME]
        self._ensure_indexes()

    def _ensure_indexes(self) -> None:
        self._collection.create_index(
            [("league", ASCENDING), ("data_type", ASCENDING)], unique=True
        )

    def get(self, league: str, data_type: str) -> dict[str, Any] | None:
        return self._collection.find_one({"league": league, "data_type": data_type})

    def upsert(
        self,
        league: str,
        data_type: str,
        season: str,
        scraped_at: datetime,
        data: dict[str, Any],
    ) -> dict[str, Any]:
        doc = {
            "league": league,
            "data_type": data_type,
            "season": season,
            "scraped_at": scraped_at,
            "data": data,
        }
        self._collection.update_one(
            {"league": league, "data_type": data_type},
            {"$set": doc},
            upsert=True,
        )
        return self.get(league, data_type)


_store: SnapshotStore | None = None


def get_store() -> SnapshotStore:
    """Lazy singleton so importing this module never opens a Mongo
    connection (handy for tests / management commands that don't need
    one). Tests monkeypatch this function to inject a mongomock-backed
    store instead of hitting a real Mongo instance."""
    global _store
    if _store is None:
        _store = SnapshotStore()
    return _store


def utcnow() -> datetime:
    return datetime.now(timezone.utc)
