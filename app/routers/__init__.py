from fastapi import APIRouter

from app.routers import hello

api_router = APIRouter()
api_router.include_router(hello.router)

__all__ = ["api_router"]
