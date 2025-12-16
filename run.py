"""
AIMS API Gateway - Application Runner

Simple script to run the FastAPI application with uvicorn
"""
import uvicorn

from app.config import settings


if __name__ == "__main__":
    uvicorn.run(
        "app.main:app",
        host=settings.server_host,
        port=settings.server_port,
        reload=True,
        log_level=settings.log_level.lower()
    )
