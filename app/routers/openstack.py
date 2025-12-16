from typing import Optional

from fastapi import APIRouter, HTTPException, status
from fastapi.concurrency import run_in_threadpool
from pydantic import AnyHttpUrl, BaseModel, Field

from app.config import settings
from app.services.openstack import OpenStackCredentials, create_connection

router = APIRouter(prefix="/openstack", tags=["OpenStack"])


class OpenStackConnectRequest(BaseModel):
    """Incoming credentials for OpenStack authentication."""

    auth_url: Optional[AnyHttpUrl] = Field(
        default=None,
        description="Keystone authentication endpoint. Uses config default when omitted.",
    )
    username: str = Field(..., description="OpenStack username")
    password: str = Field(..., description="OpenStack password", min_length=1)
    project_name: str = Field(..., description="Target project/tenant name")
    user_domain_name: str = Field(default="Default", description="User domain name")
    project_domain_name: str = Field(default="Default", description="Project domain name")
    region_name: Optional[str] = Field(default=None, description="Preferred region")
    interface: Optional[str] = Field(default=None, description="API interface (public/internal/admin)")


class OpenStackConnectResponse(BaseModel):
    """Basic metadata returned after a successful OpenStack session."""

    authenticated: bool
    project_id: Optional[str]
    user_id: Optional[str]
    region_name: Optional[str]
    interface: Optional[str]
    token_expires_at: Optional[str]


@router.post("/connect", response_model=OpenStackConnectResponse)
async def connect_openstack(request: OpenStackConnectRequest):
    """Validate OpenStack credentials and return minimal session metadata."""
    auth_url = request.auth_url or settings.os_auth_url
    if not auth_url:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="OpenStack auth_url is required. Provide it in the request or configuration.",
        )

    credentials = OpenStackCredentials(
        auth_url=str(auth_url),
        username=request.username,
        password=request.password,
        project_name=request.project_name,
        user_domain_name=request.user_domain_name,
        project_domain_name=request.project_domain_name,
        region_name=request.region_name or settings.os_region_name,
        interface=request.interface or settings.os_interface,
    )

    try:
        conn = await run_in_threadpool(create_connection, credentials)
        token_result = await run_in_threadpool(conn.authorize)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"OpenStack connection failed: {exc}",
        ) from exc

    token_expires = None
    if isinstance(token_result, dict):
        token_expires = token_result.get("expires_at")

    return OpenStackConnectResponse(
        authenticated=True,
        project_id=getattr(conn, "current_project_id", None),
        user_id=getattr(conn, "current_user_id", None),
        region_name=credentials.region_name,
        interface=credentials.interface,
        token_expires_at=token_expires,
    )
