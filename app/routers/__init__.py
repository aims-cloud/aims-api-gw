from fastapi import APIRouter

from app.routers import health, openstack

api_router = APIRouter()
api_router.include_router(health.router)
api_router.include_router(openstack.router)

__all__ = ["api_router"]
