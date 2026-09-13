class ScraperError(Exception):
    """Raised when an ESPN scrape fails: network error, non-2xx response,
    invalid JSON, or a response whose shape doesn't match what we expect.
    Callers (the /refresh view) turn this into a 502 `scrape_failed`."""
