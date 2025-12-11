from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel
from app.auth.jwt import create_access_token, verify_token

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
    # Simple demo authentication (replace with actual authentication)
    if request.username == "admin" and request.password == "password":
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
    return UserResponse(
        username=payload.get("sub"),
        user_id=payload.get("user_id")
    )
