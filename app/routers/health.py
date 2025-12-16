from datetime import datetime, timezone
from typing import Dict

from fastapi import APIRouter

from app.config import settings
from app.logging import get_logger

router = APIRouter(prefix="/health", tags=["Health"])
logger = get_logger(__name__)


def _check(detail: str, passed: bool) -> Dict[str, str]:
    """Helper to format check results."""
    return {"detail": detail, "status": "pass" if passed else "fail"}


@router.get("/", summary="Service health information")
async def health():
    """Return application health plus configuration readiness info."""
    config_checks = {
        "secret_key": _check("JWT signing key configured", bool(settings.secret_key)),
        "openstack_auth_url": _check(
            "OpenStack auth URL configured or provided via request",
            bool(settings.os_auth_url),
        ),
        "openstack_region": _check("Default OpenStack region configured", bool(settings.os_region_name)),
    }

    status_flag = all(item["status"] == "pass" for item in config_checks.values())
    overall_status = "ok" if status_flag else "degraded"

    logger.info(
        "health_check",
        status=overall_status,
        checks_passed=sum(1 for c in config_checks.values() if c["status"] == "pass"),
        checks_total=len(config_checks),
    )

    return {
        "app": settings.app_name,
        "version": settings.app_version,
        "status": overall_status,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "checks": config_checks,
    }
