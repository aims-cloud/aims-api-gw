"""OpenStack connection utilities."""

from dataclasses import dataclass
from typing import Optional

from openstack import connection
from openstack.exceptions import SDKException

from app.config import settings
from app.logging import get_logger

logger = get_logger(__name__)


@dataclass
class OpenStackCredentials:
    """Container for OpenStack authentication parameters."""

    auth_url: str
    username: str
    password: str
    project_name: str
    user_domain_name: str = "Default"
    project_domain_name: str = "Default"
    region_name: Optional[str] = None
    interface: Optional[str] = None


def create_connection(creds: OpenStackCredentials):
    """Create an OpenStack connection using openstacksdk."""
    logger.debug(
        "creating_openstack_connection",
        auth_url=creds.auth_url,
        username=creds.username,
        project_name=creds.project_name,
        region_name=creds.region_name or settings.os_region_name,
    )

    try:
        conn = connection.Connection(
            auth_url=creds.auth_url,
            username=creds.username,
            password=creds.password,
            project_name=creds.project_name,
            user_domain_name=creds.user_domain_name,
            project_domain_name=creds.project_domain_name,
            region_name=creds.region_name or settings.os_region_name,
            interface=creds.interface or settings.os_interface,
        )
        logger.debug("openstack_connection_created")
        return conn
    except SDKException as exc:
        logger.error(
            "openstack_connection_creation_failed",
            auth_url=creds.auth_url,
            username=creds.username,
            error_type=type(exc).__name__,
        )
        # Re-raise SDKException to be handled by the router layer
        raise
