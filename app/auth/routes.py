from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel
from app.auth.jwt import create_access_token, verify_token
from app.config import settings
from app.logging import get_logger

router = APIRouter()
logger = get_logger(__name__)


class LoginRequest(BaseModel):
    username: str
    password: str


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"


class UserResponse(BaseModel):
    username: str
    user_id: str


@router.post("/login", response_model=TokenResponse)
async def login(request: LoginRequest):
    """User authentication and JWT token issuance"""
    logger.info("login_attempt", username=request.username)

    # Demo authentication using credentials from .env (for development/testing only)
    if request.username == settings.demo_username and request.password == settings.demo_password:
        access_token = create_access_token(
            data={"sub": request.username, "user_id": "user_001"}
        )
        logger.info(
            "login_success",
            username=request.username,
            user_id="user_001",
        )
        return TokenResponse(access_token=access_token)

    logger.warning(
        "login_failed",
        username=request.username,
        reason="invalid_credentials",
    )
    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Incorrect username or password",
    )


@router.get("/me", response_model=UserResponse)
async def get_current_user(payload: dict = Depends(verify_token)):
    """Get authenticated user information"""
    username = payload.get("sub")
    user_id = payload.get("user_id")

    if not username or not user_id:
        logger.error(
            "invalid_token_payload",
            payload_keys=list(payload.keys()),
        )
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid token payload",
        )

    logger.debug(
        "user_info_retrieved",
        username=username,
        user_id=user_id,
    )
    return UserResponse(username=username, user_id=user_id)
