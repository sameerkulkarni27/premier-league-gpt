FROM python:3.12-slim

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    DJANGO_SETTINGS_MODULE=scraper_service.settings

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8001

# MONGO_URI / ESPN_COOKIE_HEADER / SCRAPER_STALENESS_SECONDS etc. are
# supplied at runtime via environment variables (see infra/docker-compose,
# owned by agent-infra) -- no .env file is baked into the image.
CMD ["gunicorn", "scraper_service.wsgi:application", "--bind", "0.0.0.0:8001", "--workers", "2"]
