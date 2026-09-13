from django.urls import include, path

urlpatterns = [
    path("internal/espn/", include("scraper.urls")),
]
