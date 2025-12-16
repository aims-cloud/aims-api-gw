from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    # Application
    app_name: str = "AIMS API Gateway"
    app_version: str = "0.1.0"

    # JWT
    secret_key: str
    algorithm: str = "HS256"
    access_token_expire_minutes: int = 30

    # Demo credentials for initial testing
    demo_username: str = "admin"
    demo_password: str = "password"

    # OpenStack defaults (can be overridden per request)
    os_auth_url: str | None = None
    os_region_name: str | None = None
    os_interface: str | None = "public"


settings = Settings()
