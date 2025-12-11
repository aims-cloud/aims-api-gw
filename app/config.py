from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # Application
    app_name: str = "AIMS API Gateway"
    app_version: str = "0.1.0"

    # JWT
    secret_key: str
    algorithm: str = "HS256"
    access_token_expire_minutes: int = 30

    class Config:
        env_file = ".env"


settings = Settings()
