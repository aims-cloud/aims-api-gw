from fastapi import FastAPI

from app.config import settings
from app.auth import routes as auth_routes
from app.routers import api_router
from app.logging import configure_logging, get_logger

# Initialize logging
configure_logging(
    log_level=settings.log_level,
    json_logs=settings.json_logs,
    log_to_file=settings.log_to_file,
    log_file_path=settings.log_file_path,
    log_file_max_bytes=settings.log_file_max_bytes,
    log_file_backup_count=settings.log_file_backup_count,
)
logger = get_logger(__name__)

app = FastAPI(
    title=settings.app_name,
    version=settings.app_version,
)


@app.on_event("startup")
async def startup_event():
    """Log application startup."""
    logger.info(
        "application_startup",
        app_name=settings.app_name,
        version=settings.app_version,
        log_level=settings.log_level,
        json_logs=settings.json_logs,
    )


@app.on_event("shutdown")
async def shutdown_event():
    """Log application shutdown."""
    logger.info("application_shutdown")


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
