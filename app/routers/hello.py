from fastapi import APIRouter
from app.config import settings

router = APIRouter(prefix="/hello", tags=["Hello"])


@router.get("/")
async def hello():
    """Simple hello endpoint for connectivity checks"""
    return {
        "message": "Hello from AIMS API Gateway",
        "app": settings.app_name,
        "version": settings.app_version,
    }
