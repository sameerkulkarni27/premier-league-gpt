from django.urls import path

from . import views

urlpatterns = [
    path("<str:league>/<str:data_type>/latest", views.latest, name="espn-latest"),
    path("<str:league>/<str:data_type>/refresh", views.refresh, name="espn-refresh"),
]
