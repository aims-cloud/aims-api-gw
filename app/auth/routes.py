from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel
from app.auth.jwt import create_access_token, verify_token
from app.config import settings

router = APIRouter()


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
    # Demo authentication using credentials from .env (for development/testing only)
    if request.username == settings.demo_username and request.password == settings.demo_password:
        access_token = create_access_token(
            data={"sub": request.username, "user_id": "user_001"}
        )
        return TokenResponse(access_token=access_token)

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
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid token payload",
        )

    return UserResponse(username=username, user_id=user_id)
