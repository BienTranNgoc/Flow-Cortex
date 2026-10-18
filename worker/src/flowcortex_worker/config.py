"""Worker settings, read from the environment."""

import socket
from pathlib import Path

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class StateSettings(BaseSettings):
    """The worker's private state directory; the healthcheck process reads only this."""

    model_config = SettingsConfigDict(extra="ignore")

    worker_state_dir: Path = Path("/run/flowcortex")

    @property
    def liveness_file(self) -> Path:
        return self.worker_state_dir / "alive"


class Settings(StateSettings):
    amqp_url: SecretStr
    s3_endpoint: str = Field(min_length=1)
    # The store as a video provider reaches it; URLs handed to a provider are signed for this host.
    s3_public_endpoint: str = ""
    s3_region: str = "us-east-1"
    s3_path_style: bool
    s3_upload_bucket: str = Field(min_length=1)
    s3_bucket: str = Field(min_length=1)
    s3_access_key: SecretStr
    s3_secret_key: SecretStr
    worker_id: str = Field(default_factory=socket.gethostname, min_length=1)
    heartbeat_seconds: float = Field(default=30.0, gt=0)
