from fastapi import FastAPI

from app.config import settings
from app.auth import routes as auth_routes
from app.routers import api_router

app = FastAPI(
    title=settings.app_name,
    version=settings.app_version,
)


@app.get("/")
async def root():
    """Hello API - Health check endpoint"""
    return {
        "message": "Welcome to AIMS API Gateway",
        "version": settings.app_version,
        "status": "running"
    }


# Include routers
app.include_router(auth_routes.router, prefix="/auth", tags=["Authentication"])
app.include_router(api_router)
