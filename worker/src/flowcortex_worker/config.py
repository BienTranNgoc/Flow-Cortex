"""Worker settings, read from the environment."""

import socket

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(extra="ignore")

    amqp_url: SecretStr
    s3_endpoint: str = Field(min_length=1)
    s3_region: str = "us-east-1"
    s3_path_style: bool
    s3_upload_bucket: str = Field(min_length=1)
    s3_bucket: str = Field(min_length=1)
    s3_access_key: SecretStr
    s3_secret_key: SecretStr
    worker_id: str = Field(default_factory=socket.gethostname, min_length=1)
    heartbeat_seconds: float = Field(default=30.0, gt=0)
