from fastapi import APIRouter

from app.routers import hello, openstack

api_router = APIRouter()
api_router.include_router(hello.router)
api_router.include_router(openstack.router)

__all__ = ["api_router"]
