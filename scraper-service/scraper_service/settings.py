"""
Django settings for the scraper-service project.

This is a small internal API service (no templates, no sessions, no Django
ORM models) so most Django subsystems are trimmed down. All config comes
from environment variables, loaded from the repo-root `.env` file in local
dev (see PLAN.md) or from real environment variables in CI/containers.
"""

import os
from pathlib import Path

from dotenv import load_dotenv

# scraper-service/scraper_service/settings.py -> scraper-service/ -> repo root
BASE_DIR = Path(__file__).resolve().parent.parent
REPO_ROOT = BASE_DIR.parent

# Load the repo-root .env for local dev. This file is gitignored and holds
# secrets (ESPN_COOKIE_HEADER, MONGO_URI, ...) -- never read its contents
# into logs. In CI/Docker, real env vars are used instead and this is a
# harmless no-op if the file doesn't exist.
load_dotenv(REPO_ROOT / ".env")

SECRET_KEY = os.environ.get("DJANGO_SECRET_KEY", "dev-insecure-secret-key-not-for-prod")

DEBUG = os.environ.get("DJANGO_DEBUG", "true").lower() in ("1", "true", "yes")

ALLOWED_HOSTS = [h.strip() for h in os.environ.get("DJANGO_ALLOWED_HOSTS", "*").split(",") if h.strip()]

INSTALLED_APPS = [
    "django.contrib.contenttypes",
    "scraper.apps.ScraperConfig",
]

MIDDLEWARE = [
    "django.middleware.security.SecurityMiddleware",
    "django.middleware.common.CommonMiddleware",
]

ROOT_URLCONF = "scraper_service.urls"

TEMPLATES = []

WSGI_APPLICATION = "scraper_service.wsgi.application"
ASGI_APPLICATION = "scraper_service.asgi.application"

# No Django ORM usage anywhere in this service (all persistence is via
# pymongo against Mongo directly, per PLAN.md), but Django's test runner
# and a couple of internals expect a DATABASES setting to exist. An
# in-memory sqlite DB keeps this harmless and dependency-free.
DATABASES = {
    "default": {
        "ENGINE": "django.db.backends.sqlite3",
        "NAME": ":memory:",
    }
}

USE_TZ = True
TIME_ZONE = "UTC"

DEFAULT_AUTO_FIELD = "django.db.models.BigAutoField"

# --- scraper-service specific settings -------------------------------------

# Raw browser `Cookie` header string, optional. Sent as-is on outbound ESPN
# requests as a best-effort measure against bot detection. Never log this.
ESPN_COOKIE_HEADER = os.environ.get("ESPN_COOKIE_HEADER", "")

# Mongo connection string; default matches local docker-compose Mongo.
MONGO_URI = os.environ.get("MONGO_URI", "mongodb://localhost:27017")

# Default `max_age_seconds` staleness threshold used by /latest and to
# decide "stale" on a fresh /refresh (always False there, but kept for
# consistency in case a caller omits max_age_seconds on /latest).
SCRAPER_STALENESS_SECONDS = int(os.environ.get("SCRAPER_STALENESS_SECONDS", "300"))
